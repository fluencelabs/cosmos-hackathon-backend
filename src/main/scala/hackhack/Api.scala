package hackhack

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.functor._
import cats.syntax.flatMap._

/**
  * 1. Read docker logs, parse, push to fs2.Stream
  * 2. Serve events from that stream to a websocket
  */
object Api extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    WebsocketServer
      .make[IO](8080)
      .use {
        case (_, f) =>
          IO(println("Started websocket server")) >>
            f.join as ExitCode.Success
      }
  }

}
