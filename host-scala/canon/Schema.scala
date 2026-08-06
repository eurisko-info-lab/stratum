package stratum.canon

import scala.compiletime.{erasedValue, summonInline}
import scala.deriving.Mirror
import scala.reflect.ClassTag

trait CanonCodec[A]:
  def encode(value: A): Canon
  def decode(value: Canon): Either[String, A]

trait References[A]:
  def refs(value: A): Vector[Digest]

trait Schema[A] extends CanonCodec[A] with References[A]

trait ChangeAlgebra[A]:
  def delta(previous: A, next: A): Canon
  def applyChange(current: A, change: Canon): Either[String, A]

trait ChangeComposer[A]:
  def compose(changes: Vector[Canon]): Either[String, Canon]

trait ChangeUpdater[A]:
  def update(current: A, change: Canon): Either[String, A]

trait Relation[A, B]:
  def derive(input: A): Either[String, B]

trait RelationLaws[A, B]:
  def derivePreservesSchema(input: A): Either[String, Unit]

trait Projection[A, B]:
  def project(value: A): Either[String, B]

trait DerivationGraph[A, B]:
  def schema: Schema[A]
  def relation: Relation[A, B]
  def projection: Projection[A, B]
  def change: ChangeAlgebra[A]

trait DerivationLanguage[A, B]:
  def graph: DerivationGraph[A, B]
  def run(value: A): Either[String, B]

object Projection:
  given [A](using schema: Schema[A]): Projection[A, Canon] with
    def project(value: A): Either[String, Canon] = Right(schema.encode(value))

object RelationLaws:
  given [A, B](using relation: Relation[A, B], schema: Schema[A], codec: CanonCodec[B]): RelationLaws[A, B] with
    def derivePreservesSchema(input: A): Either[String, Unit] =
      relation.derive(input) match
        case Right(value) =>
          codec.decode(codec.encode(value)) match
            case Right(decoded) if decoded == value => Right(())
            case Right(decoded) => Left(s"relation round-trip mismatch: $decoded")
            case Left(error) => Left(error)
        case Left(error) => Left(error)

object ChangeComposer:
  given [A](using algebra: ChangeAlgebra[A]): ChangeComposer[A] with
    def compose(changes: Vector[Canon]): Either[String, Canon] =
      changes match
        case Vector() => Right(Canon.Sym("noop"))
        case Vector(single) => Right(single)
        case many => Right(Canon.Node("compose", many))

object ChangeUpdater:
  given [A](using algebra: ChangeAlgebra[A]): ChangeUpdater[A] with
    def update(current: A, change: Canon): Either[String, A] = change match
      case Canon.Node("compose", steps) =>
        steps.foldLeft[Either[String, A]](Right(current)) { (acc, step) =>
          acc.flatMap(value => algebra.applyChange(value, step))
        }
      case other => algebra.applyChange(current, other)

object Relation:
  given [A](using schema: Schema[A]): Relation[A, Canon] with
    def derive(input: A): Either[String, Canon] = Right(schema.encode(input))

object DerivationGraph:
  def instance[A](schemaValue: Schema[A], relationValue: Relation[A, Canon], projectionValue: Projection[A, Canon], changeValue: ChangeAlgebra[A]): DerivationGraph[A, Canon] =
    new DerivationGraph[A, Canon]:
      def schema: Schema[A] = schemaValue
      def relation: Relation[A, Canon] = relationValue
      def projection: Projection[A, Canon] = projectionValue
      def change: ChangeAlgebra[A] = changeValue

  given [A](using ev: Schema[A], algebra: ChangeAlgebra[A]): DerivationGraph[A, Canon] with
    def schema: Schema[A] = ev
    def relation: Relation[A, Canon] = new Relation[A, Canon] { def derive(input: A): Either[String, Canon] = Right(ev.encode(input)) }
    def projection: Projection[A, Canon] = new Projection[A, Canon] { def project(value: A): Either[String, Canon] = Right(ev.encode(value)) }
    def change: ChangeAlgebra[A] = algebra

object DerivationLanguage:
  def instance[A](graphInstance: DerivationGraph[A, Canon]): DerivationLanguage[A, Canon] =
    new DerivationLanguage[A, Canon]:
      def graph: DerivationGraph[A, Canon] = graphInstance
      def run(value: A): Either[String, Canon] =
        graphInstance.relation.derive(value).flatMap { canon =>
          graphInstance.projection.project(value).map(_ => canon)
        }

  given [A](using graphInstance: DerivationGraph[A, Canon]): DerivationLanguage[A, Canon] with
    def graph: DerivationGraph[A, Canon] = graphInstance
    def run(value: A): Either[String, Canon] =
      graphInstance.relation.derive(value).flatMap { canon =>
        graphInstance.projection.project(value).map(_ => canon)
      }

trait ChangeAlgebraLaws[A]:
  def deltaRoundTrip(previous: A, next: A): Either[String, Unit]

object ChangeAlgebraLaws:
  given [A](using algebra: ChangeAlgebra[A]): ChangeAlgebraLaws[A] with
    def deltaRoundTrip(previous: A, next: A): Either[String, Unit] =
      algebra.applyChange(previous, algebra.delta(previous, next)) match
        case Right(applied) if applied == next => Right(())
        case Right(applied) => Left(s"delta round trip mismatch: $applied")
        case Left(error) => Left(error)

trait SchemaLaws[A]:
  def roundTrip(value: A): Either[String, Unit]
  def referenceTraversal(value: A): Either[String, Unit]

object SchemaLaws:
  given [A](using schema: Schema[A]): SchemaLaws[A] with
    def roundTrip(value: A): Either[String, Unit] =
      schema.decode(schema.encode(value)) match
        case Right(decoded) if decoded == value => Right(())
        case Right(decoded) => Left(s"round-trip mismatch: $decoded")
        case Left(error) => Left(error)

    def referenceTraversal(value: A): Either[String, Unit] =
      val refs = schema.refs(value)
      if refs.forall(_.hex.nonEmpty) then Right(())
      else Left(s"invalid reference traversal: $refs")

object ChangeAlgebra:
  given ChangeAlgebra[Canon] with
    def delta(previous: Canon, next: Canon): Canon = Canon.node("replace", previous, next)
    def applyChange(current: Canon, change: Canon): Either[String, Canon] = change match
      case Canon.Node("replace", Vector(_, next)) => Right(next)
      case other => Left(s"not a canon change: ${CanonText.write(other)}")

  given [A](using schema: Schema[A]): ChangeAlgebra[A] with
    def delta(previous: A, next: A): Canon = Canon.node("replace", schema.encode(previous), schema.encode(next))
    def applyChange(current: A, change: Canon): Either[String, A] = change match
      case Canon.Node("replace", Vector(_, next)) => schema.decode(next)
      case other => Left(s"not a schema change: ${CanonText.write(other)}")

  given ChangeAlgebra[Long] with
    def delta(previous: Long, next: Long): Canon = Canon.node("replace", Canon.N(BigInt(previous)), Canon.N(BigInt(next)))
    def applyChange(current: Long, change: Canon): Either[String, Long] = change match
      case Canon.Node("replace", Vector(Canon.N(_), Canon.N(next))) => Right(next.toLong)
      case other => Left(s"not a long change: ${CanonText.write(other)}")

object Schema:
  private inline def fieldSchemas[Elems <: Tuple]: Vector[Schema[Any]] =
    inline erasedValue[Elems] match
      case _: EmptyTuple => Vector.empty
      case _: (elem *: rest) =>
        val head = summonInline[Schema[elem]].asInstanceOf[Schema[Any]]
        val tail = fieldSchemas[rest]
        head +: tail

  inline given [A <: Product](using mirror: Mirror.ProductOf[A], classTag: ClassTag[A]): Schema[A] = new Schema[A]:
    private val tag = classTag.runtimeClass.getSimpleName
    private val schemas: Vector[Schema[Any]] = fieldSchemas[mirror.MirroredElemTypes]

    def encode(value: A): Canon =
      val entries = (0 until value.productArity).toVector.map { index =>
        val raw = value.productElement(index)
        schemas(index).encode(raw)
      }
      Canon.Node(tag, entries)

    def decode(value: Canon): Either[String, A] = value match
      case Canon.Node(name, args) if name == tag && args.length == value.productArity =>
        args.zipWithIndex.foldLeft[Either[String, Vector[Any]]](Right(Vector.empty)) { (acc, item) =>
          acc.flatMap { values =>
            val (arg, index) = item
            schemas(index).decode(arg).map(decoded => values :+ decoded)
          }
        }.map { values =>
          val product: Product = new Product:
            def productArity: Int = values.length
            def productElement(n: Int): Any = values(n)
            def canEqual(that: Any): Boolean = that.isInstanceOf[Product]
          mirror.fromProduct(product).asInstanceOf[A]
        }
      case other => Left(s"not a $tag: ${CanonText.write(other)}")

    def refs(value: A): Vector[Digest] =
      (0 until value.productArity).toVector.flatMap { index =>
        val raw = value.productElement(index)
        schemas(index).refs(raw)
      }

  def instance[A](enc: A => Canon, dec: Canon => Either[String, A], refFn: A => Vector[Digest]): Schema[A] = new Schema[A]:
    def encode(value: A): Canon = enc(value)
    def decode(value: Canon): Either[String, A] = dec(value)
    def refs(value: A): Vector[Digest] = refFn(value)

  given Schema[Canon] with
    def encode(value: Canon): Canon = value
    def decode(value: Canon): Either[String, Canon] = Right(value)
    def refs(value: Canon): Vector[Digest] = Canon.refs(value)

  given Schema[String] with
    def encode(value: String): Canon = Canon.S(value)
    def decode(value: Canon): Either[String, String] = value match
      case Canon.S(s) => Right(s)
      case other      => Left(s"not a string: ${CanonText.write(other)}")
    def refs(value: String): Vector[Digest] = Vector.empty

  given Schema[Boolean] with
    def encode(value: Boolean): Canon = Canon.B(value)
    def decode(value: Canon): Either[String, Boolean] = value match
      case Canon.B(v) => Right(v)
      case other      => Left(s"not a boolean: ${CanonText.write(other)}")
    def refs(value: Boolean): Vector[Digest] = Vector.empty

  given Schema[BigInt] with
    def encode(value: BigInt): Canon = Canon.N(value)
    def decode(value: Canon): Either[String, BigInt] = value match
      case Canon.N(v) => Right(v)
      case other      => Left(s"not a bigint: ${CanonText.write(other)}")
    def refs(value: BigInt): Vector[Digest] = Vector.empty

  given Schema[Long] with
    def encode(value: Long): Canon = Canon.N(BigInt(value))
    def decode(value: Canon): Either[String, Long] = value match
      case Canon.N(v) if v >= 0 && v <= Long.MaxValue => Right(v.toLong)
      case other => Left(s"not a long: ${CanonText.write(other)}")
    def refs(value: Long): Vector[Digest] = Vector.empty

  given Schema[Int] with
    def encode(value: Int): Canon = Canon.N(BigInt(value))
    def decode(value: Canon): Either[String, Int] = value match
      case Canon.N(v) if v >= Int.MinValue && v <= Int.MaxValue => Right(v.toInt)
      case other => Left(s"not an int: ${CanonText.write(other)}")
    def refs(value: Int): Vector[Digest] = Vector.empty

  given Schema[Digest] with
    def encode(value: Digest): Canon = Canon.R(value)
    def decode(value: Canon): Either[String, Digest] = value match
      case Canon.R(d) => Right(d)
      case other      => Left(s"not a digest: ${CanonText.write(other)}")
    def refs(value: Digest): Vector[Digest] = Vector.empty

  given Schema[Array[Byte]] with
    def encode(value: Array[Byte]): Canon = Canon.Y(value.toVector)
    def decode(value: Canon): Either[String, Array[Byte]] = value match
      case Canon.Y(bytes) => Right(bytes.toArray)
      case other          => Left(s"not bytes: ${CanonText.write(other)}")
    def refs(value: Array[Byte]): Vector[Digest] = Vector.empty

  given Schema[Vector[Byte]] with
    def encode(value: Vector[Byte]): Canon = Canon.Y(value)
    def decode(value: Canon): Either[String, Vector[Byte]] = value match
      case Canon.Y(bytes) => Right(bytes)
      case other          => Left(s"not bytes: ${CanonText.write(other)}")
    def refs(value: Vector[Byte]): Vector[Digest] = Vector.empty

  given [A](using schema: Schema[A]): Schema[Option[A]] with
    def encode(value: Option[A]): Canon = value match
      case None    => Canon.Node("option", Vector(Canon.Sym("none")))
      case Some(a) => Canon.Node("option", Vector(Canon.Sym("some"), schema.encode(a)))

    def decode(value: Canon): Either[String, Option[A]] = value match
      case Canon.Node("option", Vector(Canon.Sym("none"))) => Right(None)
      case Canon.Node("option", Vector(Canon.Sym("some"), body)) => schema.decode(body).map(Some(_))
      case other => Left(s"not an option: ${CanonText.write(other)}")

    def refs(value: Option[A]): Vector[Digest] = value.toVector.flatMap(schema.refs)

  given [A](using schema: Schema[A]): Schema[Vector[A]] with
    def encode(value: Vector[A]): Canon = Canon.L(value.map(schema.encode))
    def decode(value: Canon): Either[String, Vector[A]] = value match
      case Canon.L(items) => items.foldLeft[Either[String, Vector[A]]](Right(Vector.empty)) { (acc, item) =>
          acc.flatMap(v => schema.decode(item).map(v :+ _))
        }
      case other => Left(s"not a vector: ${CanonText.write(other)}")
    def refs(value: Vector[A]): Vector[Digest] = value.flatMap(schema.refs)

  given [A](using schema: Schema[A]): Schema[Set[A]] with
    def encode(value: Set[A]): Canon = Canon.L(value.toVector.map(schema.encode).sortBy(CanonText.write))
    def decode(value: Canon): Either[String, Set[A]] = value match
      case Canon.L(items) => items.foldLeft[Either[String, Set[A]]](Right(Set.empty)) { (acc, item) =>
          acc.flatMap(v => schema.decode(item).map(v + _))
        }
      case other => Left(s"not a set: ${CanonText.write(other)}")
    def refs(value: Set[A]): Vector[Digest] = value.toVector.flatMap(schema.refs)

  given [K, V](using keySchema: Schema[K], valueSchema: Schema[V]): Schema[Map[K, V]] with
    def encode(value: Map[K, V]): Canon =
      Canon.M(value.toVector.sortBy(_._1.toString).map { case (k, v) => keySchema.encode(k) -> valueSchema.encode(v) })

    def decode(value: Canon): Either[String, Map[K, V]] = value match
      case Canon.M(entries) =>
        entries.foldLeft[Either[String, Map[K, V]]](Right(Map.empty)) { (acc, entry) =>
          acc.flatMap { values =>
            for
              key <- keySchema.decode(entry._1)
              value <- valueSchema.decode(entry._2)
            yield values + (key -> value)
          }
        }
      case other => Left(s"not a map: ${CanonText.write(other)}")

    def refs(value: Map[K, V]): Vector[Digest] =
      value.toVector.flatMap { case (k, v) => keySchema.refs(k) ++ valueSchema.refs(v) }

  given [A, B](using a: Schema[A], b: Schema[B]): Schema[(A, B)] with
    def encode(value: (A, B)): Canon = Canon.L(Vector(a.encode(value._1), b.encode(value._2)))
    def decode(value: Canon): Either[String, (A, B)] = value match
      case Canon.L(Vector(x, y)) => a.decode(x).flatMap(ax => b.decode(y).map((ax, _)))
      case other => Left(s"not a pair: ${CanonText.write(other)}")
    def refs(value: (A, B)): Vector[Digest] = a.refs(value._1) ++ b.refs(value._2)

  given [A, B](using a: Schema[A], b: Schema[B]): Schema[Either[A, B]] with
    def encode(value: Either[A, B]): Canon = value match
      case Left(v) => Canon.Node("either", Vector(Canon.Sym("left"), a.encode(v)))
      case Right(v) => Canon.Node("either", Vector(Canon.Sym("right"), b.encode(v)))

    def decode(value: Canon): Either[String, Either[A, B]] = value match
      case Canon.Node("either", Vector(Canon.Sym("left"), body)) => a.decode(body).map(Left(_))
      case Canon.Node("either", Vector(Canon.Sym("right"), body)) => b.decode(body).map(Right(_))
      case other => Left(s"not an either: ${CanonText.write(other)}")

    def refs(value: Either[A, B]): Vector[Digest] = value match
      case Left(v) => a.refs(v)
      case Right(v) => b.refs(v)

object CanonCodec:
  def instance[A](enc: A => Canon, dec: Canon => Either[String, A]): CanonCodec[A] = new CanonCodec[A]:
    def encode(value: A): Canon = enc(value)
    def decode(value: Canon): Either[String, A] = dec(value)

  given [A](using schema: Schema[A]): CanonCodec[A] with
    def encode(value: A): Canon = schema.encode(value)
    def decode(value: Canon): Either[String, A] = schema.decode(value)

  given CanonCodec[Canon] with
    def encode(value: Canon): Canon = value
    def decode(value: Canon): Either[String, Canon] = Right(value)

  given CanonCodec[String] with
    def encode(value: String): Canon = Canon.S(value)
    def decode(value: Canon): Either[String, String] = value match
      case Canon.S(s) => Right(s)
      case other      => Left(s"not a string: ${CanonText.write(other)}")

  given CanonCodec[Boolean] with
    def encode(value: Boolean): Canon = Canon.B(value)
    def decode(value: Canon): Either[String, Boolean] = value match
      case Canon.B(v) => Right(v)
      case other      => Left(s"not a boolean: ${CanonText.write(other)}")

  given CanonCodec[BigInt] with
    def encode(value: BigInt): Canon = Canon.N(value)
    def decode(value: Canon): Either[String, BigInt] = value match
      case Canon.N(v) => Right(v)
      case other      => Left(s"not a bigint: ${CanonText.write(other)}")

  given CanonCodec[Long] with
    def encode(value: Long): Canon = Canon.N(BigInt(value))
    def decode(value: Canon): Either[String, Long] = value match
      case Canon.N(v) if v >= 0 && v <= Long.MaxValue => Right(v.toLong)
      case other => Left(s"not a long: ${CanonText.write(other)}")

  given CanonCodec[Int] with
    def encode(value: Int): Canon = Canon.N(BigInt(value))
    def decode(value: Canon): Either[String, Int] = value match
      case Canon.N(v) if v >= Int.MinValue && v <= Int.MaxValue => Right(v.toInt)
      case other => Left(s"not an int: ${CanonText.write(other)}")

  given CanonCodec[Digest] with
    def encode(value: Digest): Canon = Canon.R(value)
    def decode(value: Canon): Either[String, Digest] = value match
      case Canon.R(d) => Right(d)
      case other      => Left(s"not a digest: ${CanonText.write(other)}")

  given CanonCodec[Array[Byte]] with
    def encode(value: Array[Byte]): Canon = Canon.Y(value.toVector)
    def decode(value: Canon): Either[String, Array[Byte]] = value match
      case Canon.Y(bytes) => Right(bytes.toArray)
      case other          => Left(s"not bytes: ${CanonText.write(other)}")

  given CanonCodec[Vector[Byte]] with
    def encode(value: Vector[Byte]): Canon = Canon.Y(value)
    def decode(value: Canon): Either[String, Vector[Byte]] = value match
      case Canon.Y(bytes) => Right(bytes)
      case other          => Left(s"not bytes: ${CanonText.write(other)}")

  given [A](using codec: CanonCodec[A]): CanonCodec[Option[A]] with
    def encode(value: Option[A]): Canon = value match
      case None    => Canon.Node("option", Vector(Canon.Sym("none")))
      case Some(a) => Canon.Node("option", Vector(Canon.Sym("some"), codec.encode(a)))

    def decode(value: Canon): Either[String, Option[A]] = value match
      case Canon.Node("option", Vector(Canon.Sym("none"))) => Right(None)
      case Canon.Node("option", Vector(Canon.Sym("some"), body)) => codec.decode(body).map(Some(_))
      case other => Left(s"not an option: ${CanonText.write(other)}")

  given [A](using codec: CanonCodec[A]): CanonCodec[Vector[A]] with
    def encode(value: Vector[A]): Canon = Canon.L(value.map(codec.encode))
    def decode(value: Canon): Either[String, Vector[A]] = value match
      case Canon.L(items) => items.foldLeft[Either[String, Vector[A]]](Right(Vector.empty)) { (acc, item) =>
          acc.flatMap(v => codec.decode(item).map(v :+ _))
        }
      case other => Left(s"not a vector: ${CanonText.write(other)}")

  given [A](using codec: CanonCodec[A]): CanonCodec[Set[A]] with
    def encode(value: Set[A]): Canon = Canon.L(value.toVector.map(codec.encode).sortBy(CanonText.write))
    def decode(value: Canon): Either[String, Set[A]] = value match
      case Canon.L(items) => items.foldLeft[Either[String, Set[A]]](Right(Set.empty)) { (acc, item) =>
          acc.flatMap(v => codec.decode(item).map(v + _))
        }
      case other => Left(s"not a set: ${CanonText.write(other)}")

  given [A, B](using a: CanonCodec[A], b: CanonCodec[B]): CanonCodec[(A, B)] with
    def encode(value: (A, B)): Canon = Canon.L(Vector(a.encode(value._1), b.encode(value._2)))
    def decode(value: Canon): Either[String, (A, B)] = value match
      case Canon.L(Vector(x, y)) => a.decode(x).flatMap(ax => b.decode(y).map((ax, _)))
      case other => Left(s"not a pair: ${CanonText.write(other)}")

object References:
  def instance[A](f: A => Vector[Digest]): References[A] = new References[A]:
    def refs(value: A): Vector[Digest] = f(value)

  given [A](using schema: Schema[A]): References[A] with
    def refs(value: A): Vector[Digest] = schema.refs(value)

  given References[Canon] with
    def refs(value: Canon): Vector[Digest] = Canon.refs(value)

  given References[Digest] with
    def refs(value: Digest): Vector[Digest] = Vector.empty

  given [A](using r: References[A]): References[Option[A]] with
    def refs(value: Option[A]): Vector[Digest] = value.toVector.flatMap(r.refs)

  given [A](using r: References[A]): References[Vector[A]] with
    def refs(value: Vector[A]): Vector[Digest] = value.flatMap(r.refs)

  given [A](using r: References[A]): References[Set[A]] with
    def refs(value: Set[A]): Vector[Digest] = value.toVector.flatMap(r.refs)

  given [A, B](using a: References[A], b: References[B]): References[(A, B)] with
    def refs(value: (A, B)): Vector[Digest] = a.refs(value._1) ++ b.refs(value._2)
