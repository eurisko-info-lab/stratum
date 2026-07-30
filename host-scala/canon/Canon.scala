package stratum.canon

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** A content digest. Identity of every authoritative value is the SHA-256 of its canonical bytes. */
final case class Digest(bytes: Vector[Byte]):
  def hex: String = bytes.map(b => f"${b & 0xff}%02x").mkString
  override def toString: String = s"#d$hex"

object Digest:
  val Size = 32

  def of(bytes: Array[Byte]): Digest =
    val md = MessageDigest.getInstance("SHA-256")
    Digest(md.digest(bytes).toVector)

  def fromHex(s: String): Either[String, Digest] =
    if s.length != Size * 2 || !s.forall(c => "0123456789abcdef".contains(c)) then
      Left(s"not a canonical digest: $s")
    else
      Right(Digest(s.grouped(2).map(p => Integer.parseInt(p, 16).toByte).toVector))

/**
 * Canon is the single canonical value universe of the host.
 *
 * The host knows nothing about what these values mean. Every feature of the
 * system above the bootstrap boundary is expressed as Canon data.
 */
enum Canon:
  case U
  case B(value: Boolean)
  case N(value: BigInt)
  case Z(value: BigInt)
  case Y(value: Vector[Byte])
  case S(value: String)
  case Sym(value: String)
  case R(value: Digest)
  case L(items: Vector[Canon])
  case M(entries: Vector[(Canon, Canon)])
  case Node(tag: String, args: Vector[Canon])

object Canon:

  def node(tag: String, args: Canon*): Canon = Node(tag, args.toVector)
  def list(items: Canon*): Canon = L(items.toVector)
  def nat(n: Long): Canon = N(BigInt(n))
  def str(s: String): Canon = S(s)
  def sym(s: String): Canon = Sym(s)

  def map(entries: (Canon, Canon)*): Canon =
    M(entries.toVector.distinctBy(_._1).sortWith((a, b) => compare(a._1, b._1) < 0))

  def ordinal(c: Canon): Int = c match
    case U          => 0
    case B(_)       => 1
    case N(_)       => 2
    case Z(_)       => 3
    case Y(_)       => 4
    case S(_)       => 5
    case Sym(_)     => 6
    case R(_)       => 7
    case L(_)       => 8
    case M(_)       => 9
    case Node(_, _) => 10

  /** Total canonical order over all Canon values. Map keys are stored in this order. */
  def compare(a: Canon, b: Canon): Int =
    val oa = ordinal(a)
    val ob = ordinal(b)
    if oa != ob then Integer.compare(oa, ob)
    else
      (a, b) match
        case (U, U)                 => 0
        case (B(x), B(y))           => java.lang.Boolean.compare(x, y)
        case (N(x), N(y))           => x.compare(y)
        case (Z(x), Z(y))           => x.compare(y)
        case (Y(x), Y(y))           => compareBytes(x, y)
        case (S(x), S(y))           => x.compareTo(y)
        case (Sym(x), Sym(y))       => x.compareTo(y)
        case (R(x), R(y))           => compareBytes(x.bytes, y.bytes)
        case (L(x), L(y))           => compareSeq(x, y)
        case (M(x), M(y))           => compareEntries(x, y)
        case (Node(t1, a1), Node(t2, a2)) =>
          val t = t1.compareTo(t2)
          if t != 0 then t else compareSeq(a1, a2)
        case _ => 0

  private def compareBytes(x: Vector[Byte], y: Vector[Byte]): Int =
    val n = math.min(x.length, y.length)
    var i = 0
    while i < n do
      val c = Integer.compare(x(i) & 0xff, y(i) & 0xff)
      if c != 0 then return c
      i += 1
    Integer.compare(x.length, y.length)

  private def compareSeq(x: Vector[Canon], y: Vector[Canon]): Int =
    val n = math.min(x.length, y.length)
    var i = 0
    while i < n do
      val c = compare(x(i), y(i))
      if c != 0 then return c
      i += 1
    Integer.compare(x.length, y.length)

  private def compareEntries(x: Vector[(Canon, Canon)], y: Vector[(Canon, Canon)]): Int =
    val n = math.min(x.length, y.length)
    var i = 0
    while i < n do
      val ck = compare(x(i)._1, y(i)._1)
      if ck != 0 then return ck
      val cv = compare(x(i)._2, y(i)._2)
      if cv != 0 then return cv
      i += 1
    Integer.compare(x.length, y.length)

  given Ordering[Canon] = (a, b) => compare(a, b)

  /** References reachable directly from a value. Used for closure traversal. */
  def refs(c: Canon): Vector[Digest] = c match
    case R(d)        => Vector(d)
    case L(items)    => items.flatMap(refs)
    case M(entries)  => entries.flatMap((k, v) => refs(k) ++ refs(v))
    case Node(_, as) => as.flatMap(refs)
    case _           => Vector.empty

  // ---------------------------------------------------------------- binary

  private val TagU = 0
  private val TagB = 1
  private val TagN = 2
  private val TagZ = 3
  private val TagY = 4
  private val TagS = 5
  private val TagSym = 6
  private val TagR = 7
  private val TagL = 8
  private val TagM = 9
  private val TagNode = 10

  def encode(c: Canon): Array[Byte] =
    val out = new ByteArrayOutputStream()
    write(out, c)
    out.toByteArray

  def digest(c: Canon): Digest = Digest.of(encode(c))

  private def write(out: ByteArrayOutputStream, c: Canon): Unit = c match
    case U => out.write(TagU)
    case B(v) =>
      out.write(TagB); out.write(if v then 1 else 0)
    case N(v) =>
      require(v >= 0, "Nat must be non-negative")
      out.write(TagN); writeVarInt(out, v)
    case Z(v) =>
      out.write(TagZ); writeVarInt(out, zigzag(v))
    case Y(v) =>
      out.write(TagY); writeVarInt(out, BigInt(v.length)); v.foreach(b => out.write(b & 0xff))
    case S(v) =>
      out.write(TagS); writeBytes(out, v.getBytes(StandardCharsets.UTF_8))
    case Sym(v) =>
      out.write(TagSym); writeBytes(out, v.getBytes(StandardCharsets.UTF_8))
    case R(d) =>
      out.write(TagR); d.bytes.foreach(b => out.write(b & 0xff))
    case L(items) =>
      out.write(TagL); writeVarInt(out, BigInt(items.length)); items.foreach(write(out, _))
    case M(entries) =>
      out.write(TagM); writeVarInt(out, BigInt(entries.length))
      entries.foreach { (k, v) => write(out, k); write(out, v) }
    case Node(tag, args) =>
      out.write(TagNode)
      writeBytes(out, tag.getBytes(StandardCharsets.UTF_8))
      writeVarInt(out, BigInt(args.length))
      args.foreach(write(out, _))

  private def writeBytes(out: ByteArrayOutputStream, bs: Array[Byte]): Unit =
    writeVarInt(out, BigInt(bs.length))
    out.write(bs, 0, bs.length)

  private def zigzag(v: BigInt): BigInt =
    if v >= 0 then v * 2 else (-v) * 2 - 1

  private def unzigzag(v: BigInt): BigInt =
    if v % 2 == 0 then v / 2 else -((v + 1) / 2)

  /** Minimal unsigned LEB128. Non-minimal encodings decode but fail the canonicity check. */
  private def writeVarInt(out: ByteArrayOutputStream, value: BigInt): Unit =
    var v = value
    while
      val b = (v & 0x7f).toInt
      v = v >> 7
      out.write(if v == 0 then b else b | 0x80)
      v != 0
    do ()

  final class DecodeError(msg: String) extends RuntimeException(msg)

  /** Decodes canonical bytes, rejecting any non-canonical encoding. */
  def decode(bytes: Array[Byte]): Either[String, Canon] =
    try
      val cur = new Cursor(bytes)
      val v = read(cur)
      if cur.pos != bytes.length then Left("trailing bytes after canonical value")
      else if !java.util.Arrays.equals(encode(v), bytes) then Left("non-canonical encoding rejected")
      else Right(v)
    catch case e: DecodeError => Left(e.getMessage)

  private final class Cursor(val bytes: Array[Byte]):
    var pos: Int = 0
    def byte(): Int =
      if pos >= bytes.length then throw DecodeError("unexpected end of input")
      val b = bytes(pos) & 0xff
      pos += 1
      b
    def take(n: Int): Array[Byte] =
      if pos + n > bytes.length then throw DecodeError("unexpected end of input")
      val out = java.util.Arrays.copyOfRange(bytes, pos, pos + n)
      pos += n
      out

  private def readVarInt(cur: Cursor): BigInt =
    var shift = 0
    var result = BigInt(0)
    var more = true
    while more do
      val b = cur.byte()
      result = result | (BigInt(b & 0x7f) << shift)
      shift += 7
      more = (b & 0x80) != 0
      if shift > 4096 then throw DecodeError("varint too long")
    result

  private def readBytes(cur: Cursor): Array[Byte] =
    val len = readVarInt(cur)
    if len > Int.MaxValue then throw DecodeError("length overflow")
    cur.take(len.toInt)

  private def read(cur: Cursor): Canon =
    cur.byte() match
      case TagU => U
      case TagB =>
        cur.byte() match
          case 0 => B(false)
          case 1 => B(true)
          case _ => throw DecodeError("non-canonical boolean")
      case TagN => N(readVarInt(cur))
      case TagZ => Z(unzigzag(readVarInt(cur)))
      case TagY => Y(readBytes(cur).toVector)
      case TagS => S(new String(readBytes(cur), StandardCharsets.UTF_8))
      case TagSym => Sym(new String(readBytes(cur), StandardCharsets.UTF_8))
      case TagR => R(Digest(cur.take(Digest.Size).toVector))
      case TagL =>
        val n = readVarInt(cur).toInt
        L(Vector.fill(n)(read(cur)))
      case TagM =>
        val n = readVarInt(cur).toInt
        val entries = Vector.fill(n)((read(cur), read(cur)))
        // Map keys must be strictly ascending in the canonical order.
        entries.iterator.sliding(2).withPartial(false).foreach { pair =>
          compare(pair(0)._1, pair(1)._1) match
            case c if c < 0 => ()
            case 0          => throw DecodeError("duplicate map key rejected")
            case _          => throw DecodeError("unordered map key rejected")
        }
        M(entries)
      case TagNode =>
        val tag = new String(readBytes(cur), StandardCharsets.UTF_8)
        val n = readVarInt(cur).toInt
        Node(tag, Vector.fill(n)(read(cur)))
      case other => throw DecodeError(s"unknown canonical tag $other")

  /** True when the value satisfies the structural canonicity rules. */
  def isCanonical(c: Canon): Boolean = c match
    case N(v) => v >= 0
    case M(entries) =>
      val keys = entries.map(_._1)
      keys == keys.sortWith((a, b) => compare(a, b) < 0) &&
        keys.distinct.length == keys.length &&
        entries.forall((k, v) => isCanonical(k) && isCanonical(v))
    case L(items)    => items.forall(isCanonical)
    case Node(_, as) => as.forall(isCanonical)
    case _           => true
