package stratum.journal

import stratum.canon.{Canon, Digest}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import scala.collection.mutable

/**
 * The single mutation point of the host.
 *
 * Every write, delete and copy the host performs goes through here, so that a
 * sequence of commands can be run for effect and undone as a unit if any of
 * them fails.
 *
 * The file system is one participant, not the only one. Anything else that a
 * run can disturb may `enlist` a way to put itself back, and is undone with
 * everything else. That is how an effect on another node belongs here too: an
 * exchange is a write into that node's storage, so it can be recorded and
 * taken back like any other. What it cannot do is *agree* with other nodes
 * about whether the run happened. Deciding that needs more than an undo, and
 * it is settled above the bootstrap boundary, where the words for it exist.
 * This gate will not even let them be written here.
 *
 * The journal knows nothing about why a run is being made. It records the
 * prior bytes of each path it is about to disturb, and can put them back. What
 * a completed run *means* is decided above the boundary, by a judgment reading
 * the record this produces.
 *
 * When disarmed, every operation is an ordinary file operation and nothing is
 * recorded, so the normal path costs nothing.
 */
object Journal:

  /** The prior state of one path: its bytes, or absence. */
  private final case class Prior(bytes: Option[Array[Byte]], existed: Boolean)

  private var entries: Option[mutable.LinkedHashMap[Path, Prior]] = None
  private var compensations: Vector[() => Unit] = Vector.empty
  private var observed: Vector[Canon] = Vector.empty
  private var era: Long = 0

  def armed: Boolean = entries.isDefined

  /**
   * Which run is in progress, so a participant can tell one from the next and
   * record its prior state once per run.
   */
  def current: Long = era

  /** Begins recording. A run may not begin inside another. */
  def arm(): Unit =
    if armed then throw IllegalStateException("a run is already in progress: runs may not nest")
    entries = Some(mutable.LinkedHashMap.empty)
    compensations = Vector.empty
    observed = Vector.empty
    era += 1

  /** Stops recording and forgets what was recorded. */
  def disarm(): Unit =
    entries = None
    compensations = Vector.empty
    observed = Vector.empty

  /**
   * Records something the run learned from outside itself.
   *
   * A run is reproducible from its closure except where it reads the world:
   * the clock, randomness, the file system, a message from a peer. Those
   * answers cannot be recomputed, so they are written down. Everything else a
   * run asks for is derivable from the closure and is not worth recording.
   *
   * This is what lets a second implementation examine a run it did not
   * perform: given the same answers, it must reach the same verdict.
   */
  def observe(entry: Canon): Unit =
    if armed then observed = observed :+ entry

  /** What the run read from outside, in the order it asked. */
  def observations: Vector[Canon] = observed

  /**
   * Registers how to put back something that is not a file.
   *
   * Ignored when no run is in progress, so a participant may call this without
   * knowing whether anyone is recording.
   */
  def enlist(undo: () => Unit): Unit =
    if armed then compensations = compensations :+ undo

  /** Records the current state of a path, once, before it is disturbed. */
  private def note(path: Path): Unit =
    entries.foreach { seen =>
      val key = path.toAbsolutePath.normalize()
      if !seen.contains(key) then
        val prior =
          if Files.isRegularFile(key) then Prior(Some(Files.readAllBytes(key)), true)
          else Prior(None, Files.exists(key))
        seen.put(key, prior)
    }

  // ----------------------------------------------------------- operations

  def write(path: Path, bytes: Array[Byte]): Unit =
    note(path)
    Option(path.getParent).foreach(Files.createDirectories(_))
    Files.write(path, bytes)

  def writeString(path: Path, text: String): Unit =
    write(path, text.getBytes(UTF_8))

  /** Writes only a path that is new, as content-addressed storage requires. */
  def writeNew(path: Path, bytes: Array[Byte]): Unit =
    note(path)
    Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)

  def createDirectories(path: Path): Unit =
    note(path)
    Files.createDirectories(path)

  def delete(path: Path): Unit =
    note(path)
    Files.delete(path)

  def copy(source: Path, target: Path): Unit =
    note(target)
    Option(target.getParent).foreach(Files.createDirectories(_))
    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)

  // --------------------------------------------------------------- undo

  /**
   * Puts everything the run disturbed back the way it was.
   *
   * Participants that enlisted are undone first, most recent first, then the
   * files: a path that did not exist is removed, and a file that existed is
   * rewritten with its original bytes. Returns the paths restored.
   */
  def undo(): Vector[Path] =
    compensations.reverse.foreach(_())
    compensations = Vector.empty
    val restored = entries.map { seen =>
      seen.toVector.reverse.map { case (path, prior) =>
        prior.bytes match
          case Some(bytes) => Files.write(path, bytes)
          case None =>
            if Files.isRegularFile(path) then Files.deleteIfExists(path)
            else if !prior.existed && Files.isDirectory(path) then
              // Only remove a directory this run created, and only if empty.
              val stream = Files.list(path)
              val empty = try !stream.iterator().hasNext finally stream.close()
              if empty then Files.deleteIfExists(path)
        path
      }
    }.getOrElse(Vector.empty)
    entries = None
    restored

  /** What changed, as before and after digests. A path absent either side has no digest. */
  def changed(): Vector[(Path, Option[Digest], Option[Digest])] =
    entries.map { seen =>
      seen.toVector.map { case (path, prior) =>
        val before = prior.bytes.map(Digest.of)
        val after = if Files.isRegularFile(path) then Some(Digest.of(Files.readAllBytes(path))) else None
        (path, before, after)
      }.filter { case (_, before, after) => before.map(_.hex) != after.map(_.hex) }
    }.getOrElse(Vector.empty)
