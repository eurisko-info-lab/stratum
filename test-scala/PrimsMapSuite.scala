package stratum

import stratum.canon.Canon
import stratum.meta.Prims

class PrimsMapSuite extends munit.FunSuite:

  private def prim(name: String, args: Canon*): Canon =
    Prims(name, args.toVector, (kind, message) =>
      throw new IllegalArgumentException(s"$kind: $message")
    )

  private def nat(n: Int): Canon = Canon.N(BigInt(n))

  test("map primitives enumerate keys canonically regardless of insertion order") {
    val m0 = prim("mnew")
    val m1 = prim("mput", m0, Canon.S("beta"), nat(2))
    val m2 = prim("mput", m1, Canon.S("alpha"), nat(1))
    val m3 = prim("mput", m2, Canon.S("gamma"), nat(3))

    assertEquals(
      prim("mkeys", m3),
      Canon.L(Vector(Canon.S("alpha"), Canon.S("beta"), Canon.S("gamma")))
    )
    assertEquals(
      prim("mvals", m3),
      Canon.L(Vector(nat(1), nat(2), nat(3)))
    )
  }

  test("mfrom keeps the first duplicate key value") {
    val entries = Canon.L(
      Vector(
        Canon.node("entry", Canon.S("alpha"), nat(1)),
        Canon.node("entry", Canon.S("alpha"), nat(9)),
        Canon.node("entry", Canon.S("beta"), nat(2))
      )
    )

    val m = prim("mfrom", entries)
    assertEquals(prim("mget", m, Canon.S("alpha"), nat(0)), nat(1))
    assertEquals(prim("mget", m, Canon.S("beta"), nat(0)), nat(2))
  }

  test("map updates are persistent across versions") {
    val m0 = prim("mnew")
    val m1 = prim("mput", m0, Canon.S("alpha"), nat(1))
    val m2 = prim("mput", m1, Canon.S("beta"), nat(2))
    val m3 = prim("mput", m2, Canon.S("alpha"), nat(7))

    assertEquals(prim("mkeys", m1), Canon.L(Vector(Canon.S("alpha"))))
    assertEquals(prim("mkeys", m2), Canon.L(Vector(Canon.S("alpha"), Canon.S("beta"))))
    assertEquals(prim("mget", m2, Canon.S("alpha"), nat(0)), nat(1))
    assertEquals(prim("mget", m3, Canon.S("alpha"), nat(0)), nat(7))
  }

  test("mfrom and mentries round-trip the canonical map view") {
    val m0 = prim("mnew")
    val m1 = prim("mput", m0, Canon.S("gamma"), nat(3))
    val m2 = prim("mput", m1, Canon.S("alpha"), nat(1))
    val m3 = prim("mput", m2, Canon.S("beta"), nat(2))

    val roundTripped = prim("mfrom", prim("mentries", m3))
    assertEquals(roundTripped, m3)
  }
