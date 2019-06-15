package hackhack

import java.nio.file.{Files, Paths}
import java.util.concurrent.Executors

import cats.effect.concurrent.Ref
import cats.effect.{ExitCode, Resource, _}
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import fs2.{Pull, Stream}
import fs2.concurrent.SignallingRef
import fs2.io.Watcher
import fs2.io.file.pulls
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.middleware.{CORS, CORSConfig}
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame
import org.http4s.websocket.WebSocketFrame.Text

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.concurrent.duration._
import scala.language.higherKinds
import scala.sys.process.Process

case class Event(line: String)
object Event {
  implicit val encodeEvent: Encoder[Event] = deriveEncoder
  implicit val decodeEvent: Decoder[Event] = deriveDecoder
}

case class WebsocketServer[F[_]: ConcurrentEffect: Timer: ContextShift](
    streams: Ref[F, Map[String, fs2.Stream[F, Event]]],
    signal: SignallingRef[F, Boolean]
) extends Http4sDsl[F] {

  private def randomStream: fs2.Stream[F, Event] =
    fs2.Stream
      .fromIterator[F, String](
        Process("cat /dev/urandom").lineStream_!.iterator)
      .map(s => Event(s.take(10)))

  private def routes(): HttpRoutes[F] =
    HttpRoutes.of[F] {
      case GET -> Root / "websocket" / "start" / "random" / key =>
        println(s"Starting streaming for random $key")
        for {
          stream <- streams.modify { map =>
            map
              .get(key)
              .fold {
                val s = randomStream
                  .evalTap(e => Sync[F].delay(println(s"event: $e")))
                  .interruptWhen(signal)
                map.updated(key, s) -> s
              }(map -> _)
          }
          frames = stream.map(e => Text(e.asJson.noSpaces))
          ws <- WebSocketBuilder[F].build(
            frames,
            _.evalMap(e => Sync[F].delay(println(s"from $key: $e"))))
        } yield ws

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

      case (GET | POST) -> Root / "create" / key =>
        val stream =
          fs2.Stream
//            .eval(Sync[F].delay(Files.createTempFile("websocket", key)))
            .eval(Sync[F].delay(Paths.get("/tmp/docker.log")))
            .evalTap(path => Sync[F].delay(println(s"created $path")))
            .flatMap(FileStream.stream[F])
            .map(Event(_))
            .evalTap(e => Sync[F].delay(println(s"event $key $e")))

        Sync[F].delay(println(s"Creating stream for $key")) >>
          streams.update(map =>
            map.get(key).fold(map.updated(key, stream)) { _ =>
              println(s"Stream for $key already exists")
              map
          }) >>
          Ok()
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
        streams <- Ref.of[F, Map[String, fs2.Stream[F, Event]]](Map.empty)
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
