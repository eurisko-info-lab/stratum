package stratum

import java.nio.file.{Files, Path, Paths}
import stratum.canon.{Canon, CanonText}
import stratum.grammar.GrammarMachine0

/** Smalltalk's grammar is the part of this deployment that has to be exactly
  * right: message precedence is not a convention here, it is the meaning of
  * every line of code the browser will show. These are the cases that would
  * silently change what a program does if the grammar drifted.
  */
class SmalltalkSyntaxSuite extends munit.FunSuite:

  private val grammar: GrammarMachine0.Grammar =
    val text = Files.readString(Paths.get("applications/smalltalk/smalltalk.generated.grammar"))
    val canon = CanonText.read(text).fold(m => sys.error(m), identity)
    GrammarMachine0.load(canon).fold(m => sys.error(m), identity)

  private def parse(source: String): String =
    GrammarMachine0.parse(grammar, source).fold(m => s"error: $m", CanonText.write)

  test("unary messages bind tighter than binary ones") {
    assertEquals(
      parse("1 + 2 factorial."),
      "(Evaluate (BinarySend (Number 1) (BinaryStep + (Send (Number 2) (Variable factorial)))))"
    )
  }

  test("binary messages bind tighter than keyword ones") {
    assertEquals(
      parse("n > 1 ifTrue: [ ^n ]."),
      "(Evaluate (KeywordSend (BinarySend (Variable n) (BinaryStep > (Number 1)))" +
        " (KeywordArgument ifTrue: (Block (ReturnFinal (Variable n))))))"
    )
  }

  test("binary messages associate to the left, without precedence among them") {
    assertEquals(
      parse("3 + 4 * 2."),
      "(Evaluate (BinarySend (Number 3) (BinarySteps (BinaryStep + (Number 4)) (BinaryStep * (Number 2)))))"
    )
  }

  test("a keyword message takes all its parts") {
    assertEquals(
      parse("d at: 1 put: 2."),
      "(Evaluate (KeywordSend (Variable d) (KeywordArguments" +
        " (KeywordArgument at: (Number 1)) (KeywordArgument put: (Number 2)))))"
    )
  }

  test("a block carries its parameters and its statements") {
    assertEquals(
      parse("[ :a :b | a + b ]."),
      "(Evaluate (BlockWith (BlockParameters (BlockParameter a) (BlockParameter b))" +
        " (EvaluateFinal (BinarySend (Variable a) (BinaryStep + (Variable b))))))"
    )
  }

  test("a method is a chunk of source belonging to a class") {
    assert(parse("Counter >> bump [ ^count + 1 ]").startsWith("(Method Counter (UnaryPattern bump)"))
  }

  test("source survives being printed and read again") {
    val source = "Counter >> at: k put: v [ table at: k put: v. ^v ]"
    val tree = GrammarMachine0.parse(grammar, source).fold(m => sys.error(m), identity)
    val printed = GrammarMachine0.print(grammar, tree).fold(m => sys.error(m), identity)
    val again = GrammarMachine0.parse(grammar, printed).fold(m => sys.error(m), identity)
    assertEquals(again, tree)
  }

  test("a method can declare temporaries") {
    assert(
      parse("Stack >> pop [ | value | ^value ]")
        .startsWith("(MethodWithTemporaries Stack (UnaryPattern pop) (Temporaries (Name value))")
    )
  }

  test("several temporaries are declared between one pair of bars") {
    assert(parse("A >> b [ | i j | ^i ]").contains("(Temporaries (Names (Name i) (Name j)))"))
  }

  test("a block can declare temporaries after its parameters") {
    assert(
      parse("[ :i | | double | double := i ].")
        .startsWith("(Evaluate (BlockWithBoth (BlockParameter i) (Temporaries (Name double))")
    )
  }

  test("a comment is not part of the program") {
    assertEquals(parse("\"the answer\" 42."), "(Evaluate (Number 42))")
  }
