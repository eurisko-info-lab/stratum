package stratum.cli

import java.nio.file.Paths

/**
 * StratumHost0 command entry point.
 *
 * The host offers exactly one asymmetry: it accepts a program, a goal, a
 * closure, a constitution, a budget and capabilities. Everything else is
 * a feature artifact.
 */
object Stratum:

  def main(args: Array[String]): Unit =
    val root = Paths.get(sys.props.getOrElse("stratum.root", System.getProperty("user.dir"))).toAbsolutePath.normalize()
    var result: CommandResult = CommandResult.fail("not run")
    val thread = Thread(null, () => result = Cli.run(root, args.toVector), "stratum", 512L * 1024 * 1024)
    thread.start()
    thread.join()
    if result.lines.nonEmpty then println(result.output)
    if result.code != 0 then sys.exit(result.code)
