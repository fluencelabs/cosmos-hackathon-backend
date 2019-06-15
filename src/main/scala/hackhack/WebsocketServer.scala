package hackhack

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

import scala.concurrent.duration._
import scala.language.higherKinds

case class WebsocketServer[F[_]: ConcurrentEffect: Timer: ContextShift](
    appRegistry: AppRegistry[F],
    signal: SignallingRef[F, Boolean]
) extends Http4sDsl[F] {

  private def routes(): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case GET -> Root / "websocket" / appName =>
        appRegistry.stream(appName).value.flatMap {
          case Left(e) =>
            println(s"Error while getting stream for $appName: $e")
            InternalServerError(s"Error while getting stream for $appName: $e")

          case Right(stream) =>
            WebSocketBuilder[F].build(
              stream.map(e => Text(e.asJson.noSpaces)),
              _.evalMap(e => Sync[F].delay(println(s"from $appName: $e")))
            )
        }

      case (GET | POST) -> Root / "create" / appName / seedHost / LongVar(
            seedPort) / hash =>
        appRegistry
          .run(appName, Peer(seedHost, seedPort.toShort), hash)
          .value
          .flatMap {
            case Left(e) =>
              println(s"Error while running app $appName: $e")
              InternalServerError(s"Error while running app $appName: $e")

            case Right(height) =>
              Ok(s"""
               |{
               | "consensusHeight": $height
               |}
             """.stripMargin)

          }
      // TODO: list of registered apps
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

  def make[F[_]: Timer: ContextShift](port: Int, appRegistry: AppRegistry[F])(
      implicit F: ConcurrentEffect[F])
    : Resource[F, (WebsocketServer[F], Fiber[F, Unit])] =
    Resource.make(
      for {
        signal <- SignallingRef[F, Boolean](false)
        server = WebsocketServer(appRegistry, signal)
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
