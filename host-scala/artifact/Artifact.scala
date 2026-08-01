package stratum.artifact

import stratum.journal.Journal
import stratum.canon.{Canon, CanonText, Digest}

import java.nio.file.{Files, Path, StandardOpenOption}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** An immutable artifact envelope. The host never interprets `kind`. */
final case class Artifact(kind: String, body: Canon):
  def toCanon: Canon = Canon.Node("artifact", Vector(Canon.Sym(kind), body))
  def bytes: Array[Byte] = Canon.encode(toCanon)
  def digest: Digest = Digest.of(bytes)
  def refs: Vector[Digest] = Canon.refs(body)

object Artifact:
  def fromCanon(c: Canon): Either[String, Artifact] = c match
    case Canon.Node("artifact", Vector(Canon.Sym(kind), body)) => Right(Artifact(kind, body))
    case other => Left(s"not an artifact envelope: ${CanonText.write(other)}")

  def decode(bytes: Array[Byte]): Either[String, Artifact] =
    Canon.decode(bytes).flatMap(fromCanon)

/** Content addressed storage. Identity is the digest of canonical artifact bytes. */
trait Cas:
  def get(d: Digest): Option[Artifact]
  def put(a: Artifact): Digest
  def has(d: Digest): Boolean = get(d).isDefined
  def digests: Vector[Digest]

final class MemoryCas(initial: Map[Digest, Artifact] = Map.empty) extends Cas:
  private val store = mutable.LinkedHashMap.from(initial)

  def get(d: Digest): Option[Artifact] = store.get(d)

  def put(a: Artifact): Digest =
    val d = a.digest
    store.getOrElseUpdate(d, a)
    d

  def digests: Vector[Digest] = store.keys.toVector.sortBy(_.hex)

/** A CAS backed by a directory of `<digest>.canon` files. */
final class DirectoryCas(val root: Path) extends Cas:
  Journal.createDirectories(root)
  private val cache = mutable.HashMap.empty[Digest, Artifact]

  private def pathOf(d: Digest): Path = root.resolve(s"${d.hex}.canon")

  def get(d: Digest): Option[Artifact] =
    cache.get(d).orElse {
      val p = pathOf(d)
      if !Files.exists(p) then None
      else
        Artifact.decode(Files.readAllBytes(p)) match
          case Right(a) if a.digest == d =>
            cache.put(d, a)
            Some(a)
          case _ => None
    }

  def put(a: Artifact): Digest =
    val d = a.digest
    val p = pathOf(d)
    if !Files.exists(p) then
      Journal.writeNew(p, a.bytes)
    cache.put(d, a)
    d

  def digests: Vector[Digest] =
    val stream = Files.list(root)
    try
      stream
        .iterator()
        .asScala
        .map(_.getFileName.toString)
        .filter(_.endsWith(".canon"))
        .flatMap(n => Digest.fromHex(n.stripSuffix(".canon")).toOption)
        .toVector
        .sortBy(_.hex)
    finally stream.close()

/** A CAS that reads through several stores and writes to the first. */
final class LayeredCas(primary: Cas, fallbacks: Vector[Cas]) extends Cas:
  def get(d: Digest): Option[Artifact] =
    primary.get(d).orElse(fallbacks.iterator.flatMap(_.get(d)).nextOption())
  def put(a: Artifact): Digest = primary.put(a)
  def digests: Vector[Digest] = (primary.digests ++ fallbacks.flatMap(_.digests)).distinct.sortBy(_.hex)

object Closure:

  /** All digests reachable from `root`, or the first missing digest. */
  def traverse(cas: Cas, root: Digest): Either[Digest, Vector[Digest]] =
    val seen = mutable.LinkedHashSet.empty[Digest]
    val stack = mutable.Stack(root)
    while stack.nonEmpty do
      val d = stack.pop()
      if !seen.contains(d) then
        cas.get(d) match
          case None => return Left(d)
          case Some(a) =>
            seen += d
            a.refs.foreach(r => if !seen.contains(r) then stack.push(r))
    Right(seen.toVector.sortBy(_.hex))

  def isComplete(cas: Cas, root: Digest): Boolean = traverse(cas, root).isRight

  /** Copies the closure of `root` from `from` into `to`. */
  def copy(from: Cas, to: Cas, root: Digest): Either[Digest, Vector[Digest]] =
    traverse(from, root).map { ds =>
      ds.foreach(d => from.get(d).foreach(to.put))
      ds
    }
