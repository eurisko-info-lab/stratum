package stratum.remote

import stratum.lsp.{Server, Service}

import java.net.{InetAddress, ServerSocket, Socket}
import java.nio.file.{Files, Path, Paths}

object RemoteServer:

  def main(args: Array[String]): Unit =
    val options = args.toVector
      .sliding(2)
      .collect { case Vector(key, value) if key.startsWith("--") => key.drop(2) -> value }
      .toMap
    val root = Paths.get(System.getProperty("user.dir")).toAbsolutePath.normalize()
    val world = options.get("world") match
      case Some(value) => root.resolve(value)
      case None        => fail("usage: stratum-remote --world <dir> [--host <address>] [--port <port>]")
    val host = options.getOrElse("host", "127.0.0.1")
    val port = options.get("port").flatMap(_.toIntOption).getOrElse(2087)
    val loaded = Service.load(root, world) match
      case Right(value) => value
      case Left(message) => fail(message)

    val listener = ServerSocket(port, 50, InetAddress.getByName(host))
    println(s"Stratum remote service listening on $host:$port for ${relative(root, world)}")
    try
      while true do
        val socket = listener.accept()
        val service = Service(root, world, loaded.world)
        val thread = Thread(() => serve(service, socket), s"stratum-remote-${socket.getRemoteSocketAddress}")
        thread.setDaemon(true)
        thread.start()
    finally listener.close()

  private def serve(service: Service, socket: Socket): Unit =
    try
      Server.serve(
        service,
        socket.getInputStream,
        socket.getOutputStream,
        path => readSource(service, path)
      )
    finally socket.close()

  private def readSource(service: Service, path: String): Option[String] =
    val application = service.worldDir.toAbsolutePath.normalize()
    val candidate = service.root.resolve(path).toAbsolutePath.normalize()
    Option.when(candidate.startsWith(application) && Files.isRegularFile(candidate))(Files.readString(candidate))

  private def relative(root: Path, path: Path): String =
    if path.startsWith(root) then root.relativize(path).toString else path.toString

  private def fail(message: String): Nothing =
    System.err.println(message)
    sys.exit(2)