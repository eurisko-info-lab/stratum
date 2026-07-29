package stratum

import stratum.canon.{Canon, CanonText, Digest}

/** Canonical identity: decode(encode(x)) = x, and non-canonical encodings are rejected. */
class CanonSuite extends munit.FunSuite:

  private val samples: Vector[Canon] = Vector(
    Canon.U,
    Canon.B(true),
    Canon.B(false),
    Canon.N(BigInt(0)),
    Canon.N(BigInt("123456789012345678901234567890")),
    Canon.Z(BigInt(-42)),
    Canon.Y(Vector[Byte](0, 1, -1, 127)),
    Canon.S("hello \" world\n"),
    Canon.Sym("Judgment"),
    Canon.R(Digest.of("seed".getBytes)),
    Canon.L(Vector(Canon.nat(1), Canon.sym("a"), Canon.U)),
    Canon.map(Canon.sym("b") -> Canon.nat(2), Canon.sym("a") -> Canon.nat(1)),
    Canon.node("app", Canon.node("lam", Canon.nat(0)), Canon.node("var", Canon.nat(1)))
  )

  test("binary round trip") {
    samples.foreach { c =>
      val bytes = Canon.encode(c)
      assertEquals(Canon.decode(bytes), Right(c), s"failed for ${CanonText.write(c)}")
    }
  }

  test("text round trip") {
    samples.foreach { c =>
      val text = CanonText.write(c)
      assertEquals(CanonText.read(text), Right(c), s"failed for $text")
    }
  }

  test("identity is the digest of canonical bytes") {
    samples.foreach { c =>
      assertEquals(Canon.digest(c), Digest.of(Canon.encode(c)))
    }
  }

  test("non minimal varints are rejected") {
    // Nat 1 encoded with a redundant continuation byte.
    val bad = Array[Byte](2, 0x81.toByte, 0x00.toByte)
    assert(Canon.decode(bad).isLeft, "non canonical varint must be rejected")
  }

  test("unsorted map encodings are rejected") {
    val unsorted = Canon.M(Vector(Canon.sym("b") -> Canon.nat(2), Canon.sym("a") -> Canon.nat(1)))
    val bytes = Canon.encode(unsorted)
    assert(Canon.decode(bytes).isRight, "the encoder writes what it is given")
    assert(!Canon.isCanonical(unsorted), "an unsorted map is not canonical")
  }

  test("trailing bytes are rejected") {
    val bytes = Canon.encode(Canon.nat(7)) :+ 0.toByte
    assert(Canon.decode(bytes).isLeft)
  }

  test("canonical order is total and stable") {
    val sorted = samples.sortWith((a, b) => Canon.compare(a, b) < 0)
    assertEquals(sorted.sortWith((a, b) => Canon.compare(a, b) < 0), sorted)
  }
