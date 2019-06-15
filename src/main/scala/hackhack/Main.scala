package hackhack

import java.nio.ByteBuffer
import java.nio.file.Files

import cats.data.EitherT
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.syntax.functor._
import cats.syntax.either._
import cats.syntax.flatMap._
import com.softwaremill.sttp.{SttpBackend, Uri}
import hackhack.ipfs.IpfsStore
import hackhack.utils.EitherTSttpBackend

/**
  * 1. Read docker logs, parse, push to fs2.Stream
  * 2. Serve events from that stream to a websocket
  */
object Main extends IOApp {
  type STTP = SttpBackend[EitherT[IO, Throwable, ?], fs2.Stream[IO, ByteBuffer]]
  private val sttpResource: Resource[
    IO,
    SttpBackend[EitherT[IO, Throwable, ?], fs2.Stream[IO, ByteBuffer]]] =
    Resource.make(IO(EitherTSttpBackend[IO]()))(sttpBackend ⇒
      IO(sttpBackend.close()))

  override def run(args: List[String]): IO[ExitCode] = {

    val ipfsUri = Uri("ipfs2.fluence.one", 5001)

    sttpResource.use { implicit sttp =>
      val ipfsStore = IpfsStore[IO](ipfsUri)
      for {
        runner <- Runner.make[IO]
        appRegistry <- AppRegistry.make[IO](ipfsStore, runner)
        _ <- appRegistry.loadExistingContainers().value.map(IO.fromEither)
        _ <- WebsocketServer
          .make[IO](8080, appRegistry)
          .use {
            case (_, f) =>
              IO(println("Started websocket server")) >> f.join
          }
      } yield ExitCode.Success
    }
  }

}
