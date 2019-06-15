package hackhack

import java.nio.file.{Path, Paths}
import java.util.concurrent.Executors

import cats.data.EitherT
import cats.effect._
import cats.effect.concurrent.{Deferred, Ref}
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, Defer, Monad}
import hackhack.docker.params.{DockerImage, DockerParams}
import io.circe.Json
import io.circe.parser.parse

import scala.concurrent.ExecutionContext
import scala.language.{higherKinds, postfixOps}
import scala.sys.process._

case class Runner[F[_]: Monad: LiftIO: ContextShift: Defer: Concurrent](
    lastPort: Ref[F, Short],
    blockingCtx: ExecutionContext =
      ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
) {

//  nsd --log_level "debug" start --moniker stage-02 --address tcp://0.0.0.0:26658 --p2p.laddr tcp://0.0.0.0:26656 --rpc.laddr tcp://0.0.0.0:26657 --p2p.persistent_peers d53cf2cb91514edb41441503e8a11f004023f2ee@207.154.210.151:26656

  val ContainerImage = "cosmos-runner"

  private def nextPortThousand =
    lastPort.modify(p => (p + 1 toShort, p * 1000 toShort))

  private def dockerCmd(name: String, peer: String, binaryPath: Path) =
    for {
      portThousand <- nextPortThousand
      process = DockerParams
        .build()
        .port(30656 + portThousand toShort, 26656)
        .port(30657 + portThousand toShort, 26657)
        .port(30658 + portThousand toShort, 26658)
        .option("--name", name)
        .option("-e", s"PEER=$peer") //TODO: Add $PEER usage to docker script
        .volume(binaryPath.toAbsolutePath.toString, "/binary") //TODO: download binary from IPFS
        .prepared(DockerImage(ContainerImage, "latest"))
        .daemonRun()
        .process
    } yield process

  private def getLogPath(containerId: String): EitherT[F, Throwable, Path] =
    EitherT(IO(s"docker inspect $containerId".!!).attempt.to[F])
      .subflatMap(inspect => parse(inspect))
      .subflatMap(
        _.asArray
          .flatMap(_.headOption)
          .fold(
            new Exception(s"Can't parse array from docker inspect $containerId")
              .asLeft[Json]
          )(_.asRight)
      )
      .subflatMap(_.as[String])
      .flatMap(p => EitherT(IO(Paths.get(p)).attempt.to[F]))

  private def log(str: String) = IO(println(str)).to[F]

  def run(name: String,
          peer: String,
          binaryPath: Path): EitherT[F, Throwable, fs2.Stream[F, String]] = {
    val container = for {
      cmd <- dockerCmd(name, peer, binaryPath)
      _ <- log(s"$name got dockerCmd")
      idPromise <- Deferred[F, String]
      _ <- Concurrent[F].start(
        IO(cmd.!!).attempt
          .onError {
            case e => IO(println(s"Failed to run container $name: $e"))
          }
          .to[F]
          .flatMap(_.fold(_ => Applicative[F].unit, idPromise.complete)))
      _ <- log(s"$name signaled docker container to start")
      containerId <- idPromise.get
      _ <- log(s"$name docker container started $containerId")
    } yield containerId

    for {
      containerId <- EitherT.liftF(container)
      logPath <- getLogPath(containerId)
      stream = FileStream.stream[F](logPath)
    } yield stream
  }

}
