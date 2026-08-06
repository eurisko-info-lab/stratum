package stratum.cap

import stratum.artifact.{Artifact, Cas}
import stratum.journal.Journal
import stratum.canon.{Canon, Digest}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.collection.mutable

/** A capability request is `(request <name> [args...])` and never carries feature meaning. */
final case class CapabilityRequest(name: String, args: Vector[Canon]):
  def toCanon: Canon = Canon.Node("request", Vector(Canon.Sym(name), Canon.L(args)))

final case class CapabilityResponse(ok: Boolean, value: Canon):
  def toCanon: Canon =
    Canon.Node(if ok then "ok" else "denied", Vector(value))

/**
 * The generic capability ABI.
 *
 * Handlers perform operations. They never decide entitlement, acceptance,
 * agreement or settlement: those are decided by Meta programs.
 */
trait CapabilityHandler:
  def names: Set[String]
  def handle(req: CapabilityRequest): CapabilityResponse

object CapabilityHandler:
  /**
   * The answers a run cannot recompute for itself.
   *
   * Everything else a capability offers is derivable from the closure, which
   * is content addressed, so asking again gives the same answer and writing it
   * down proves nothing. These do not: they are where a run touches the world.
   */
  val environmental: Set[String] =
    Set("now", "random-bytes", "receive", "pending", "fs-read", "fs-exists", "fs-list")

  def compose(handlers: CapabilityHandler*): CapabilityHandler = new CapabilityHandler:
    private val table = handlers.flatMap(h => h.names.map(_ -> h)).toMap
    def names: Set[String] = table.keySet
    def handle(req: CapabilityRequest): CapabilityResponse =
      val response = table.get(req.name) match
        case Some(h) => h.handle(req)
        case None    => CapabilityResponse(false, Canon.Sym("unknown-capability"))
      if environmental.contains(req.name) then
        Journal.observe(
          Canon.map(
            Canon.Sym("request") -> req.toCanon,
            Canon.Sym("response") -> response.toCanon
          )
        )
      response

  val empty: CapabilityHandler = new CapabilityHandler:
    def names: Set[String] = Set.empty
    def handle(req: CapabilityRequest): CapabilityResponse =
      CapabilityResponse(false, Canon.Sym("unknown-capability"))

/** Hashing over canonical bytes. */
final class HashCapability extends CapabilityHandler:
  def names: Set[String] = Set("hash", "digest-of-bytes")

  def handle(req: CapabilityRequest): CapabilityResponse = (req.name, req.args) match
    case ("hash", Vector(v)) =>
      CapabilityResponse(true, Canon.R(Canon.digest(v)))
    case ("digest-of-bytes", Vector(Canon.Y(bytes))) =>
      CapabilityResponse(true, Canon.R(Digest.of(bytes.toArray)))
    case _ => CapabilityResponse(false, Canon.Sym("bad-request"))

/**
 * Texts and byte strings, named as numbers.
 *
 * A program that cannot look inside a text or a byte string can still be told
 * what numbers are in one, and can still name one by its numbers. `textBytes`
 * already crossed a text as bytes; this is the same crossing without the
 * framing, and in a shape a program with no bytes of its own can read.
 */
final class OctetCapability extends CapabilityHandler:
  def names: Set[String] = Set("text-octets", "octets-of-bytes", "bytes-of-octets")

  private def octets(bytes: Iterable[Byte]): Canon =
    Canon.L(bytes.iterator.map(b => Canon.N(BigInt(b & 0xff))).toVector)

  def handle(req: CapabilityRequest): CapabilityResponse = (req.name, req.args) match
    case ("text-octets", Vector(Canon.S(text))) =>
      CapabilityResponse(true, octets(text.getBytes(StandardCharsets.UTF_8)))
    case ("octets-of-bytes", Vector(Canon.Y(bytes))) =>
      CapabilityResponse(true, octets(bytes))
    case ("bytes-of-octets", Vector(Canon.L(items))) =>
      val bytes = items.collect { case Canon.N(v) if v >= 0 && v < 256 => v.toByte }
      if bytes.length != items.length then CapabilityResponse(false, Canon.Sym("not-an-octet"))
      else CapabilityResponse(true, Canon.Y(bytes))
    case _ => CapabilityResponse(false, Canon.Sym("bad-request"))

/** Read and write access to the content addressed store. */
final class CasCapability(cas: Cas) extends CapabilityHandler:
  def names: Set[String] = Set("cas-get", "cas-put", "cas-has")

  def handle(req: CapabilityRequest): CapabilityResponse = (req.name, req.args) match
    case ("cas-get", Vector(Canon.R(d))) =>
      cas.get(d) match
        case Some(a) => CapabilityResponse(true, a.toCanon)
        case None    => CapabilityResponse(false, Canon.Sym("missing-artifact"))
    case ("cas-has", Vector(Canon.R(d))) =>
      CapabilityResponse(true, Canon.B(cas.has(d)))
    case ("cas-put", Vector(v)) =>
      Artifact.fromCanon(v) match
        case Right(a) => CapabilityResponse(true, Canon.R(cas.put(a)))
        case Left(m)  => CapabilityResponse(false, Canon.S(m))
    case _ => CapabilityResponse(false, Canon.Sym("bad-request"))

/** Filesystem access restricted to a sandbox root. */
final class FileCapability(root: Path) extends CapabilityHandler:
  private val base = root.toAbsolutePath.normalize()

  def names: Set[String] = Set("fs-read", "fs-write", "fs-list", "fs-exists")

  private def resolve(rel: String): Option[Path] =
    val p = base.resolve(rel).toAbsolutePath.normalize()
    if p.startsWith(base) then Some(p) else None

  def handle(req: CapabilityRequest): CapabilityResponse = (req.name, req.args) match
    case ("fs-read", Vector(Canon.S(rel))) =>
      resolve(rel) match
        case Some(p) if Files.exists(p) =>
          CapabilityResponse(true, Canon.Y(Files.readAllBytes(p).toVector))
        case Some(_) => CapabilityResponse(false, Canon.Sym("missing-file"))
        case None    => CapabilityResponse(false, Canon.Sym("path-escape"))
    case ("fs-exists", Vector(Canon.S(rel))) =>
      CapabilityResponse(true, Canon.B(resolve(rel).exists(Files.exists(_))))
    case ("fs-write", Vector(Canon.S(rel), Canon.Y(bytes))) =>
      resolve(rel) match
        case Some(p) =>
          Journal.createDirectories(p.getParent)
          Journal.write(p, bytes.toArray)
          CapabilityResponse(true, Canon.U)
        case None => CapabilityResponse(false, Canon.Sym("path-escape"))
    case _ => CapabilityResponse(false, Canon.Sym("bad-request"))

/**
 * Signature primitives.
 *
 * The host verifies the cryptographic equation only. Whether a signer is
 * entitled to act over a subject is decided by Meta programs.
 */
final class SignatureCapability extends CapabilityHandler:
  def names: Set[String] = Set("sign", "verify", "public-key")

  private def mac(secret: String, message: Canon): Vector[Byte] =
    val md = MessageDigest.getInstance("SHA-256")
    md.update(secret.getBytes(StandardCharsets.UTF_8))
    md.update(0.toByte)
    md.update(Canon.encode(message))
    md.digest().toVector

  def handle(req: CapabilityRequest): CapabilityResponse = (req.name, req.args) match
    case ("public-key", Vector(Canon.S(secret))) =>
      CapabilityResponse(true, Canon.S(SignatureCapability.register(secret)))
    case ("sign", Vector(Canon.S(secret), message)) =>
      SignatureCapability.register(secret)
      CapabilityResponse(true, Canon.Y(mac(secret, message)))
    case ("verify", Vector(Canon.S(pk), message, Canon.Y(sig))) =>
      // The host verifies the cryptographic equation only.
      SignatureCapability.secretFor(pk) match
        case Some(secret) => CapabilityResponse(true, Canon.B(mac(secret, message) == sig))
        case None         => CapabilityResponse(true, Canon.B(false))
    case _ => CapabilityResponse(false, Canon.Sym("bad-request"))

object SignatureCapability:
  private val known = mutable.LinkedHashMap.empty[String, String]

  def register(secret: String): String =
    val pk = Digest.of(("pk:" + secret).getBytes(StandardCharsets.UTF_8)).hex.take(16)
    known.put(pk, secret)
    pk

  def secretFor(pk: String): Option[String] = known.get(pk)

/** Deterministic clock and randomness, seeded from the closure for reproducibility. */
final class DeterministicEnvironmentCapability(seed: String) extends CapabilityHandler:
  private var counter = 0L

  def names: Set[String] = Set("now", "random-bytes")

  def handle(req: CapabilityRequest): CapabilityResponse = (req.name, req.args) match
    case ("now", _) =>
      counter += 1
      CapabilityResponse(true, Canon.N(BigInt(counter)))
    case ("random-bytes", Vector(Canon.N(n))) =>
      counter += 1
      val d = Digest.of(s"$seed:$counter".getBytes(StandardCharsets.UTF_8))
      CapabilityResponse(true, Canon.Y(d.bytes.take(n.toInt)))
    case _ => CapabilityResponse(false, Canon.Sym("bad-request"))

/**
 * GrammarMachine0 exposed as a capability.
 *
 * The host interprets grammar artifacts. It has no syntax of its own.
 */
final class GrammarCapability(cas: Cas) extends CapabilityHandler:
  def names: Set[String] = Set("grammar-parse", "grammar-print", "grammar-lex")

  private def grammarOf(d: Digest): Either[String, stratum.grammar.GrammarMachine0.Grammar] =
    cas.get(d) match
      case None    => Left(s"missing grammar artifact ${d.hex}")
      case Some(a) => stratum.grammar.GrammarMachine0.load(a.body)

  def handle(req: CapabilityRequest): CapabilityResponse = (req.name, req.args) match
    case ("grammar-parse", Vector(Canon.R(d), Canon.S(text))) =>
      grammarOf(d).flatMap(g => stratum.grammar.GrammarMachine0.parse(g, text)) match
        case Right(v) => CapabilityResponse(true, v)
        case Left(m)  => CapabilityResponse(false, Canon.S(m))
    case ("grammar-print", Vector(Canon.R(d), value)) =>
      grammarOf(d).flatMap(g => stratum.grammar.GrammarMachine0.print(g, value)) match
        case Right(s) => CapabilityResponse(true, Canon.S(s))
        case Left(m)  => CapabilityResponse(false, Canon.S(m))
    case ("grammar-lex", Vector(Canon.R(d), Canon.S(text))) =>
      // The token classes the grammar declares, located in the text. Keyed, so
      // the reader dispatches on nothing, and reported with the declared kind
      // rather than the token's name.
      grammarOf(d) match
        case Left(m) => CapabilityResponse(false, Canon.S(m))
        case Right(g) =>
          stratum.grammar.GrammarMachine0.lex(g, text) match
            case Left(m) => CapabilityResponse(false, Canon.S(m))
            case Right(tokens) =>
              val declared = g.tokens.map(t => t.name -> t.kind).toMap
              CapabilityResponse(
                true,
                Canon.L(tokens.map { t =>
                  Canon.M(
                    Vector(
                      Canon.Sym("kind") -> Canon.Sym(declared.getOrElse(t.kind, t.kind)),
                      Canon.Sym("length") -> Canon.nat(t.text.length),
                      Canon.Sym("offset") -> Canon.nat(t.offset)
                    )
                  )
                })
              )
    case _ => CapabilityResponse(false, Canon.Sym("bad-request"))

/** In-process transport used for closure and branch exchange. */
final class TransportCapability extends CapabilityHandler:
  private val queues = mutable.LinkedHashMap.empty[String, mutable.Queue[Canon]]
  private var recordedFor: Long = -1

  def names: Set[String] = Set("send", "receive", "open-connection", "close-connection", "pending")

  private def queue(peer: String): mutable.Queue[Canon] =
    queues.getOrElseUpdate(peer, mutable.Queue.empty)

  /**
   * Joins the run in progress, once, before anything is exchanged.
   *
   * An exchange is a write into another node's storage, so it is undoable like
   * any other write, and a run that fails must leave no message behind.
   */
  private def record(): Unit =
    if Journal.armed && recordedFor != Journal.current then
      recordedFor = Journal.current
      val prior = queues.map { case (peer, q) => peer -> q.toVector }.toVector
      Journal.enlist { () =>
        queues.clear()
        prior.foreach { case (peer, items) => queues.put(peer, mutable.Queue.from(items)) }
        recordedFor = -1
      }

  def handle(req: CapabilityRequest): CapabilityResponse = (req.name, req.args) match
    case ("open-connection", Vector(Canon.S(peer))) =>
      record()
      queue(peer)
      CapabilityResponse(true, Canon.U)
    case ("close-connection", Vector(Canon.S(peer))) =>
      record()
      queues.remove(peer)
      CapabilityResponse(true, Canon.U)
    case ("send", Vector(Canon.S(peer), message)) =>
      record()
      queue(peer).enqueue(message)
      CapabilityResponse(true, Canon.U)
    case ("pending", Vector(Canon.S(peer))) =>
      CapabilityResponse(true, Canon.N(BigInt(queue(peer).size)))
    case ("receive", Vector(Canon.S(peer))) =>
      record()
      val q = queue(peer)
      if q.isEmpty then CapabilityResponse(true, Canon.Node("empty", Vector.empty))
      else CapabilityResponse(true, Canon.Node("message", Vector(q.dequeue())))
    case _ => CapabilityResponse(false, Canon.Sym("bad-request"))

object Capabilities:
  /** The standard host capability set, sandboxed to `workspace` and backed by `cas`. */
  def standard(cas: Cas, workspace: Path, seed: String): CapabilityHandler =
    CapabilityHandler.compose(
      new HashCapability,
      new OctetCapability,
      new CasCapability(cas),
      new GrammarCapability(cas),
      new FileCapability(workspace),
      new SignatureCapability,
      new DeterministicEnvironmentCapability(seed),
      new TransportCapability
    )
