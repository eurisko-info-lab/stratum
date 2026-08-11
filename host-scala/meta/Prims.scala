package stratum.meta

import stratum.canon.{Canon, CanonText}

import java.util.{WeakHashMap => JWeakHashMap}

/**
 * The fixed primitive set of MetaMachine0.
 *
 * Every primitive is structural or arithmetic. None of them knows about any
 * feature of the system built above the bootstrap boundary.
 */
object Prims:

  type Fail = (String, String) => Nothing

  private def num(c: Canon, fail: Fail): BigInt = c match
    case Canon.N(v) => v
    case Canon.Z(v) => v
    case other      => fail("type-error", s"expected a number, found ${CanonText.write(other)}")

  private def mkNum(v: BigInt): Canon = if v >= 0 then Canon.N(v) else Canon.Z(v)

  private def list(c: Canon, fail: Fail): Vector[Canon] = c match
    case Canon.L(items) => items
    case other          => fail("type-error", s"expected a list, found ${CanonText.write(other)}")

  private object HamtMap:
    private val BitsPerLevel = 5
    private val Mask = (1 << BitsPerLevel) - 1
    private val MaxShift = 32

    sealed trait Node
    case object Empty extends Node
    final case class BitmapIndexed(bitmap: Int, children: Vector[Node]) extends Node
    final case class Leaf(hash: Int, key: Canon, value: Canon) extends Node
    final case class Collision(hash: Int, entries: Vector[(Canon, Canon)]) extends Node

    final case class Map(root: Node, size: Int):
      def contains(key: Canon): Boolean = get(key).nonEmpty
      def get(key: Canon): Option[Canon] = lookup(root, key.hashCode, key, 0)

      def put(key: Canon, value: Canon): Map =
        val (nextRoot, inserted) = insert(root, key.hashCode, key, value, 0)
        if nextRoot eq root then this else Map(nextRoot, if inserted then size + 1 else size)

      def delete(key: Canon): Map =
        val (nextRootOpt, removed) = remove(root, key.hashCode, key, 0)
        if !removed then this
        else Map(nextRootOpt.getOrElse(Empty), size - 1)

      def canonicalEntries: Vector[(Canon, Canon)] =
        collect(root).sortWith((a, b) => Canon.compare(a._1, b._1) < 0)

    val empty: Map = Map(Empty, 0)

    def fromEntries(entries: Vector[(Canon, Canon)]): Map =
      entries.foldLeft(empty) { case (acc, (k, v)) =>
        // Preserve the historical mfrom rule: first key occurrence wins.
        if acc.contains(k) then acc else acc.put(k, v)
      }

    private def mask(hash: Int, shift: Int): Int = (hash >>> shift) & Mask
    private def bitpos(maskValue: Int): Int = 1 << maskValue
    private def index(bitmap: Int, bit: Int): Int = Integer.bitCount(bitmap & (bit - 1))

    private def collect(node: Node): Vector[(Canon, Canon)] = node match
      case Empty => Vector.empty
      case Leaf(_, key, value) => Vector(key -> value)
      case Collision(_, entries) => entries
      case BitmapIndexed(_, children) => children.flatMap(collect)

    private def lookup(node: Node, hash: Int, key: Canon, shift: Int): Option[Canon] = node match
      case Empty => None
      case Leaf(_, k, v) => if k == key then Some(v) else None
      case Collision(_, entries) => entries.find(_._1 == key).map(_._2)
      case BitmapIndexed(bitmap, children) =>
        val bit = bitpos(mask(hash, shift))
        if (bitmap & bit) == 0 then None
        else lookup(children(index(bitmap, bit)), hash, key, shift + BitsPerLevel)

    private def insert(node: Node, hash: Int, key: Canon, value: Canon, shift: Int): (Node, Boolean) = node match
      case Empty => (Leaf(hash, key, value), true)
      case leaf @ Leaf(leafHash, leafKey, leafValue) =>
        if leafKey == key then
          if leafValue == value then (leaf, false) else (Leaf(leafHash, leafKey, value), false)
        else if leafHash == hash then
          (Collision(hash, Vector((leafKey, leafValue), (key, value))), true)
        else
          (mergeDifferentHashes(leafHash, leaf, hash, Leaf(hash, key, value), shift), true)
      case collision @ Collision(collisionHash, entries) =>
        if collisionHash == hash then
          val idx = entries.indexWhere(_._1 == key)
          if idx < 0 then (Collision(hash, entries :+ (key -> value)), true)
          else if entries(idx)._2 == value then (collision, false)
          else (Collision(hash, entries.updated(idx, key -> value)), false)
        else
          (mergeDifferentHashes(collisionHash, collision, hash, Leaf(hash, key, value), shift), true)
      case bitmapNode @ BitmapIndexed(bitmap, children) =>
        val bit = bitpos(mask(hash, shift))
        val idx = index(bitmap, bit)
        if (bitmap & bit) == 0 then
          val nextChildren = children.patch(idx, Vector(Leaf(hash, key, value)), 0)
          (BitmapIndexed(bitmap | bit, nextChildren), true)
        else
          val child = children(idx)
          val (nextChild, inserted) = insert(child, hash, key, value, shift + BitsPerLevel)
          if nextChild eq child then (bitmapNode, inserted)
          else (BitmapIndexed(bitmap, children.updated(idx, nextChild)), inserted)

    private def remove(node: Node, hash: Int, key: Canon, shift: Int): (Option[Node], Boolean) = node match
      case Empty => (Some(Empty), false)
      case leaf @ Leaf(_, leafKey, _) =>
        if leafKey == key then (None, true) else (Some(leaf), false)
      case collision @ Collision(collisionHash, entries) =>
        if collisionHash != hash then (Some(collision), false)
        else
          val idx = entries.indexWhere(_._1 == key)
          if idx < 0 then (Some(collision), false)
          else
            val next = entries.patch(idx, Vector.empty, 1)
            next.length match
              case 0 => (None, true)
              case 1 =>
                val (k, v) = next.head
                (Some(Leaf(hash, k, v)), true)
              case _ => (Some(Collision(hash, next)), true)
      case bitmapNode @ BitmapIndexed(bitmap, children) =>
        val bit = bitpos(mask(hash, shift))
        if (bitmap & bit) == 0 then (Some(bitmapNode), false)
        else
          val idx = index(bitmap, bit)
          val child = children(idx)
          val (nextChildOpt, removed) = remove(child, hash, key, shift + BitsPerLevel)
          if !removed then (Some(bitmapNode), false)
          else
            nextChildOpt match
              case Some(nextChild) =>
                if nextChild eq child then (Some(bitmapNode), true)
                else (Some(BitmapIndexed(bitmap, children.updated(idx, nextChild))), true)
              case None =>
                val nextBitmap = bitmap & ~bit
                val nextChildren = children.patch(idx, Vector.empty, 1)
                if nextChildren.isEmpty then (None, true)
                else if nextChildren.length == 1 then (Some(nextChildren.head), true)
                else (Some(BitmapIndexed(nextBitmap, nextChildren)), true)

    private def mergeDifferentHashes(hashA: Int, nodeA: Node, hashB: Int, nodeB: Node, shift: Int): Node =
      if shift >= MaxShift then
        val merged = collect(nodeA) ++ collect(nodeB)
        Collision(hashA, merged)
      else
        val maskA = mask(hashA, shift)
        val maskB = mask(hashB, shift)
        val bitA = bitpos(maskA)
        val bitB = bitpos(maskB)
        if maskA != maskB then
          if maskA < maskB then BitmapIndexed(bitA | bitB, Vector(nodeA, nodeB))
          else BitmapIndexed(bitA | bitB, Vector(nodeB, nodeA))
        else
          BitmapIndexed(bitA, Vector(mergeDifferentHashes(hashA, nodeA, hashB, nodeB, shift + BitsPerLevel)))

  private val mapCache = new JWeakHashMap[Canon, HamtMap.Map]()

  private def mapValue(c: Canon, fail: Fail): HamtMap.Map = c match
    case m @ Canon.M(entries) =>
      mapCache.synchronized {
        Option(mapCache.get(m)).getOrElse {
          val built = HamtMap.fromEntries(entries)
          mapCache.put(m, built)
          built
        }
      }
    case other => fail("type-error", s"expected a map, found ${CanonText.write(other)}")

  private def canonMap(map: HamtMap.Map): Canon =
    val canon = Canon.M(map.canonicalEntries)
    mapCache.synchronized { mapCache.put(canon, map) }
    canon

  private def entries(c: Canon, fail: Fail): Vector[(Canon, Canon)] =
    mapValue(c, fail).canonicalEntries

  private def string(c: Canon, fail: Fail): String = c match
    case Canon.S(v) => v
    case other      => fail("type-error", s"expected a string, found ${CanonText.write(other)}")

  private def bytes(c: Canon, fail: Fail): Vector[Byte] = c match
    case Canon.Y(v) => v
    case other      => fail("type-error", s"expected bytes, found ${CanonText.write(other)}")

  private def mkMap(es: Vector[(Canon, Canon)]): Canon =
    canonMap(HamtMap.fromEntries(es))

  def apply(name: String, args: Vector[Canon], fail: Fail): Canon =
    def arity(n: Int): Unit =
      if args.length != n then fail("arity-error", s"primitive $name expects $n arguments, got ${args.length}")

    name match
      // ---------------------------------------------------------- equality
      case "eq" =>
        arity(2); Canon.B(args(0) == args(1))
      case "ne" =>
        arity(2); Canon.B(args(0) != args(1))
      case "cmp" =>
        arity(2)
        val c = Canon.compare(args(0), args(1))
        Canon.N(BigInt(if c < 0 then 0 else if c == 0 then 1 else 2))

      // -------------------------------------------------------- arithmetic
      case "add" => arity(2); mkNum(num(args(0), fail) + num(args(1), fail))
      case "sub" => arity(2); mkNum(num(args(0), fail) - num(args(1), fail))
      case "mul" => arity(2); mkNum(num(args(0), fail) * num(args(1), fail))
      case "div" =>
        arity(2)
        val d = num(args(1), fail)
        if d == 0 then fail("division-by-zero", "div by zero") else mkNum(num(args(0), fail) / d)
      case "mod" =>
        arity(2)
        val d = num(args(1), fail)
        if d == 0 then fail("division-by-zero", "mod by zero") else mkNum(num(args(0), fail) % d)
      case "min" => arity(2); mkNum(num(args(0), fail).min(num(args(1), fail)))
      case "max" => arity(2); mkNum(num(args(0), fail).max(num(args(1), fail)))
      case "lt"  => arity(2); Canon.B(num(args(0), fail) < num(args(1), fail))
      case "le"  => arity(2); Canon.B(num(args(0), fail) <= num(args(1), fail))
      case "gt"  => arity(2); Canon.B(num(args(0), fail) > num(args(1), fail))
      case "ge"  => arity(2); Canon.B(num(args(0), fail) >= num(args(1), fail))

      // ------------------------------------------------------------- logic
      case "not" =>
        arity(1)
        args(0) match
          case Canon.B(b) => Canon.B(!b)
          case other      => fail("type-error", s"not expects a boolean, found ${CanonText.write(other)}")
      case "and" =>
        arity(2)
        (args(0), args(1)) match
          case (Canon.B(a), Canon.B(b)) => Canon.B(a && b)
          case _                        => fail("type-error", "and expects booleans")
      case "or" =>
        arity(2)
        (args(0), args(1)) match
          case (Canon.B(a), Canon.B(b)) => Canon.B(a || b)
          case _                        => fail("type-error", "or expects booleans")

      // ------------------------------------------------------- type tests
      case "is-unit"  => arity(1); Canon.B(args(0) == Canon.U)
      case "is-bool"  => arity(1); Canon.B(args(0).isInstanceOf[Canon.B])
      case "is-nat"   => arity(1); Canon.B(args(0).isInstanceOf[Canon.N])
      case "is-int"   => arity(1); Canon.B(args(0).isInstanceOf[Canon.Z])
      case "is-bytes" => arity(1); Canon.B(args(0).isInstanceOf[Canon.Y])
      case "is-str"   => arity(1); Canon.B(args(0).isInstanceOf[Canon.S])
      case "is-sym"   => arity(1); Canon.B(args(0).isInstanceOf[Canon.Sym])
      case "is-ref"   => arity(1); Canon.B(args(0).isInstanceOf[Canon.R])
      case "is-list"  => arity(1); Canon.B(args(0).isInstanceOf[Canon.L])
      case "is-map"   => arity(1); Canon.B(args(0).isInstanceOf[Canon.M])
      case "is-node"  => arity(1); Canon.B(args(0).isInstanceOf[Canon.Node])

      // ------------------------------------------------------------- nodes
      case "tag" =>
        arity(1)
        args(0) match
          case Canon.Node(t, _) => Canon.Sym(t)
          case other            => fail("type-error", s"tag expects a node, found ${CanonText.write(other)}")
      case "args" =>
        arity(1)
        args(0) match
          case Canon.Node(_, a) => Canon.L(a)
          case other            => fail("type-error", s"args expects a node, found ${CanonText.write(other)}")
      case "node" =>
        arity(2)
        args(0) match
          case Canon.Sym(t) => Canon.Node(t, list(args(1), fail))
          case other        => fail("type-error", s"node expects a symbol tag, found ${CanonText.write(other)}")

      // ------------------------------------------------------------- lists
      case "len"   => arity(1); Canon.N(BigInt(list(args(0), fail).length))
      case "nil?"  => arity(1); Canon.B(list(args(0), fail).isEmpty)
      case "cons"  => arity(2); Canon.L(args(0) +: list(args(1), fail))
      case "snoc"  => arity(2); Canon.L(list(args(0), fail) :+ args(1))
      case "head" =>
        arity(1)
        val l = list(args(0), fail)
        if l.isEmpty then fail("empty-list", "head of empty list") else l.head
      case "tail" =>
        arity(1)
        val l = list(args(0), fail)
        if l.isEmpty then fail("empty-list", "tail of empty list") else Canon.L(l.tail)
      case "nth" =>
        arity(2)
        val l = list(args(0), fail)
        val i = num(args(1), fail)
        if i < 0 || i >= l.length then fail("index-out-of-range", s"index $i out of range") else l(i.toInt)
      case "append" => arity(2); Canon.L(list(args(0), fail) ++ list(args(1), fail))
      case "rev"    => arity(1); Canon.L(list(args(0), fail).reverse)
      case "take"   => arity(2); Canon.L(list(args(0), fail).take(num(args(1), fail).toInt))
      case "drop"   => arity(2); Canon.L(list(args(0), fail).drop(num(args(1), fail).toInt))
      case "sort" =>
        arity(1)
        Canon.L(list(args(0), fail).sortWith((a, b) => Canon.compare(a, b) < 0))
      case "distinct" => arity(1); Canon.L(list(args(0), fail).distinct)
      case "contains" => arity(2); Canon.B(list(args(0), fail).contains(args(1)))
      case "index-of" =>
        arity(2)
        val i = list(args(0), fail).indexOf(args(1))
        if i < 0 then Canon.Node("none", Vector.empty) else Canon.Node("some", Vector(Canon.nat(i)))
      case "set-nth" =>
        arity(3)
        val l = list(args(0), fail)
        val i = num(args(1), fail)
        if i < 0 || i >= l.length then fail("index-out-of-range", s"index $i out of range")
        else Canon.L(l.updated(i.toInt, args(2)))
      case "insert-at" =>
        arity(3)
        val l = list(args(0), fail)
        val i = num(args(1), fail)
        if i < 0 || i > l.length then fail("index-out-of-range", s"index $i out of range")
        else Canon.L(l.take(i.toInt) ++ Vector(args(2)) ++ l.drop(i.toInt))
      case "remove-at" =>
        arity(2)
        val l = list(args(0), fail)
        val i = num(args(1), fail)
        if i < 0 || i >= l.length then fail("index-out-of-range", s"index $i out of range")
        else Canon.L(l.take(i.toInt) ++ l.drop(i.toInt + 1))

      // -------------------------------------------------------------- maps
      case "mnew"  => arity(0); canonMap(HamtMap.empty)
      case "msize" => arity(1); Canon.N(BigInt(mapValue(args(0), fail).size))
      case "mhas"  => arity(2); Canon.B(mapValue(args(0), fail).contains(args(1)))
      case "mget" =>
        arity(3)
        mapValue(args(0), fail).get(args(1)).getOrElse(args(2))
      case "mput" =>
        arity(3)
        canonMap(mapValue(args(0), fail).put(args(1), args(2)))
      case "mdel" =>
        arity(2)
        canonMap(mapValue(args(0), fail).delete(args(1)))
      case "mkeys" =>
        arity(1); Canon.L(entries(args(0), fail).map(_._1))
      case "mvals" =>
        arity(1); Canon.L(entries(args(0), fail).map(_._2))
      case "mentries" =>
        arity(1); Canon.L(entries(args(0), fail).map((k, v) => Canon.node("entry", k, v)))
      case "mfrom" =>
        arity(1)
        val loaded = list(args(0), fail).map {
          case Canon.Node("entry", Vector(k, v)) => k -> v
          case other => fail("type-error", s"mfrom expects entry nodes, found ${CanonText.write(other)}")
        }
        mkMap(loaded)

      // ----------------------------------------------------------- strings
      //
      // A character is a Unicode scalar value, not a UTF-16 code unit. The
      // distinction is invisible below U+10000 and decisive above it: counting
      // code units reports two for one character, and slicing between them
      // yields an unpaired surrogate, which is not a scalar value and has no
      // canonical encoding. The other host counts scalars, and a value that
      // one host cannot encode is not a value.
      case "scat" => arity(2); Canon.S(string(args(0), fail) + string(args(1), fail))
      case "slen" =>
        arity(1)
        val s = string(args(0), fail)
        Canon.N(BigInt(s.codePointCount(0, s.length)))
      case "ssub" =>
        arity(3)
        val s = string(args(0), fail)
        val characters = s.codePointCount(0, s.length)
        val from = num(args(1), fail).toInt
        val to = num(args(2), fail).toInt
        if from < 0 || to > characters || from > to then
          fail("index-out-of-range", "substring out of range")
        else Canon.S(s.substring(s.offsetByCodePoints(0, from), s.offsetByCodePoints(0, to)))
      case "sym->str" =>
        arity(1)
        args(0) match
          case Canon.Sym(s) => Canon.S(s)
          case other        => fail("type-error", s"sym->str expects a symbol, found ${CanonText.write(other)}")
      case "str->sym" => arity(1); Canon.Sym(string(args(0), fail))
      case "nat->str" => arity(1); Canon.S(num(args(0), fail).toString)
      case "str->nat" =>
        arity(1)
        val s = string(args(0), fail)
        if s.nonEmpty && s.forall(_.isDigit) then Canon.N(BigInt(s))
        else fail("parse-error", s"not a natural number: $s")

      // ------------------------------------------------------------- bytes
      case "blen" => arity(1); Canon.N(BigInt(bytes(args(0), fail).length))
      case "bcat" => arity(2); Canon.Y(bytes(args(0), fail) ++ bytes(args(1), fail))

      // -------------------------------------------------------- identity
      case "digest" => arity(1); Canon.R(Canon.digest(args(0)))
      case "encode" => arity(1); Canon.Y(Canon.encode(args(0)).toVector)
      case "decode" =>
        arity(1)
        Canon.decode(bytes(args(0), fail).toArray) match
          case Right(v) => Canon.Node("some", Vector(v))
          case Left(_)  => Canon.Node("none", Vector.empty)
      case "ref->str" =>
        arity(1)
        args(0) match
          case Canon.R(d) => Canon.S(d.hex)
          case other      => fail("type-error", s"ref->str expects a reference, found ${CanonText.write(other)}")

      case other => fail("unknown-primitive", s"unknown primitive $other")
