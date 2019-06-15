package hackhack

import java.nio.file.Paths

import cats.effect.concurrent.Ref
import cats.effect.{ExitCode, Resource, _}
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import fs2.Stream
import fs2.concurrent.SignallingRef
import io.circe.syntax._
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.middleware.{CORS, CORSConfig}
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame.Text
import io.circe.parser.parse

import scala.concurrent.duration._
import scala.language.higherKinds

case class WebsocketServer[F[_]: ConcurrentEffect: Timer: ContextShift](
    streams: Ref[F, Map[String, fs2.Stream[F, Log]]],
    signal: SignallingRef[F, Boolean]
) extends Http4sDsl[F] {

  private def routes(): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case GET -> Root / "websocket" / "start" / "file" / key =>
        streams.get.flatMap { map =>
          map.get(key) match {
            case None =>
              BadRequest(s"There's no stream for $key, create it first")
            case Some(stream) =>
              WebSocketBuilder[F].build(
                stream.map(e => Text(e.asJson.noSpaces)),
                _.evalMap(e => Sync[F].delay(println(s"from $key: $e")))
              )
          }
        }

      case (GET | POST) -> Root / "create" / appName =>
        val stream =
          fs2.Stream
//            .eval(Sync[F].delay(Files.createTempFile("websocket", appName)))
            .eval(Sync[F].delay(Paths.get("/tmp/stage-04-ns.log")))
            .evalTap(path => Sync[F].delay(println(s"created $path")))
            .flatMap(FileStream.stream[F])
            .map(parse(_).toOption)
            .unNone
            .map(_.hcursor.get[String]("log").toOption)
            .unNone
            .filter(_.nonEmpty)
            .evalTap(line => Sync[F].delay(println(s"line $appName $line")))
            .map(Log(appName, _))
            .unNone
            .evalTap(log => Sync[F].delay(println(s"log $appName $log")))

        Sync[F].delay(println(s"Creating stream for $appName")) >>
          streams.update(map =>
            map.get(appName).fold(map.updated(appName, stream)) { _ =>
              println(s"Stream for $appName already exists")
              map
          }) >> Ok("""
              |{
              | "consensusHeight": 150
              |}
            """.stripMargin)

      // TODO: list of registered apps
      // TODO: endpoint for consensusHeight
    }

  def close(): F[Unit] = signal.set(true)

  def start(port: Int): Stream[F, ExitCode] =
    for {
      exitCode <- Stream.eval(Ref[F].of(ExitCode.Success))
      server <- BlazeServerBuilder[F]
        .bindHttp(port)
        .withHttpApp(CORS[F, F](routes().orNotFound, corsConfig))
        .serveWhile(signal, exitCode)
    } yield server

  val corsConfig = CORSConfig(
    anyOrigin = true,
    anyMethod = true,
    allowedMethods = Some(Set("GET", "POST")),
    allowCredentials = true,
    maxAge = 1.day.toSeconds
  )
}

object WebsocketServer {
  import cats.syntax.flatMap._
  import cats.syntax.functor._

  def make[F[_]: Timer: ContextShift](port: Int)(
      implicit F: ConcurrentEffect[F])
    : Resource[F, (WebsocketServer[F], Fiber[F, Unit])] =
    Resource.make(
      for {
        streams <- Ref.of[F, Map[String, fs2.Stream[F, Log]]](Map.empty)
        signal <- SignallingRef[F, Boolean](false)
        server = WebsocketServer(streams, signal)
        fiber <- Concurrent[F].start(Backoff.default {
          server
            .start(port)
            .compile
            .drain
            .attemptT
        })
      } yield (server, fiber)
    ) { case (s, f) => s.close() >> f.cancel }
}
