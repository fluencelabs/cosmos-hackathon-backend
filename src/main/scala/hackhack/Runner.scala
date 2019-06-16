package hackhack

import java.nio.file.{Path, Paths}
import java.util.concurrent.Executors

import cats.data.EitherT
import cats.effect._
import cats.effect.concurrent.{Deferred, Ref}
import cats.instances.list._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Defer, Monad, Traverse}
import hackhack.docker.params.{DockerImage, DockerParams}
import io.circe.Json
import io.circe.parser.parse
import scodec.bits.ByteVector

import scala.concurrent.ExecutionContext
import scala.language.{higherKinds, postfixOps}
import scala.sys.process._
import scala.util.Try

case class Runner[F[_]: Monad: LiftIO: ContextShift: Defer: Concurrent](
    lastPort: Ref[F, Short],
    blockingCtx: ExecutionContext =
      ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
) {

//  nsd --log_level "debug" start --moniker stage-02 --address tcp://0.0.0.0:26658 --p2p.laddr tcp://0.0.0.0:26656 --rpc.laddr tcp://0.0.0.0:26657 --p2p.persistent_peers d53cf2cb91514edb41441503e8a11f004023f2ee@207.154.210.151:26656

  val ContainerImage = "folexflu/cosmos-runner"

  private def nextPort =
    lastPort.modify(p => (p + 3 toShort, p))

  private def dockerCmd(name: String,
                        peer: String,
                        rpcPort: Short,
                        binaryHash: ByteVector,
                        binaryPath: Path,
                        genesisPath: Path) =
    for {
      port <- nextPort
      cmd = DockerParams
        .build()
        .port(port, 26656)
        .port(port + 1 toShort, 26657)
        .port(port + 2 toShort, 26658)
        .option("--name", s"fisherman-$name")
        .option("-e", s"PEER=$peer")
        .option("-e", s"RPCPORT=$rpcPort")
        .option("-e", s"MONIKER=$name")
        .option("-e", s"BINARY_HASH=${binaryHash.toHex}")
        .option("-e", s"BINARY_PATH=${binaryPath.toAbsolutePath.toString}")
        .volume(binaryPath.toAbsolutePath.toString, "/binary")
        .volume(genesisPath.toAbsolutePath.toString, "/root/genesis.json")
        .prepared(DockerImage(ContainerImage, "ubuntu"))
        .daemonRun()
      _ = println(s"Running docker ${cmd.command.mkString(" ")}")
      process = cmd.process
    } yield process

  private def inspect(containerId: String): EitherT[F, Throwable, Json] =
    EitherT(IO(s"docker inspect $containerId".!!).attempt.to[F])
      .subflatMap { inspect =>
        parse(inspect)
      }
      .subflatMap { json =>
        json.asArray
          .flatMap(_.headOption)
          .fold(
            new Exception(s"Can't parse array from docker inspect $containerId")
              .asLeft[Json]
          )(_.asRight)
      }

  private def getLogPath(containerId: String): EitherT[F, Throwable, Path] =
    inspect(containerId)
      .subflatMap { json =>
        json.hcursor.get[String]("LogPath")
      }
      .flatMap { p =>
        EitherT(IO(Paths.get(p)).attempt.to[F])
      }

  def listFishermenContainers: EitherT[F, Throwable, List[App]] =
    for {
      ps <- EitherT(
        IO(
          Seq("docker",
              "ps",
              "-a",
              "-f",
              "name=fisherman",
              "--format",
              "{{.Names}} {{.ID}}").!!).attempt.to[F])
      (names, ids) = ps
        .split("\n")
        .filter(_.nonEmpty)
        .toList
        .map(_.split(" "))
        .map(a => a.head -> a.last)
        .unzip
      inspects <- Traverse[List].sequence(ids.map(inspect))
      envs <- Traverse[List]
        .sequence(inspects.map(
          _.hcursor.downField("Config").get[List[String]]("Env").toEitherT[F]))
        .leftMap(identity[Throwable])

      envMap <- Try(
        envs.map(_.map(_.split("=")).map(a => a.head -> a.last).toMap)).toEither
        .toEitherT[F]

      apps <- Try {
        ids.zip(names).zip(envMap).map {
          case ((id, name), env) =>
            val rpcPort = env("RPCPORT").toShort
            val peer = env
              .get("PEER")
              .map(_.split(Array('@', ':')))
              .map(a => Peer(a(1), rpcPort))
              .get
            val binaryHash = ByteVector.fromValidHex(env("BINARY_HASH"))
            val binaryPath = Paths.get(env("BINARY_PATH"))
            val cleanName = name.replace("fisherman-", "")
            App(cleanName, id, peer, binaryHash, binaryPath)
        }
      }.toEither.toEitherT[F]
    } yield apps

  private def log(str: String) = IO(println(str)).to[F]

  def run(name: String,
          peer: String,
          rpcPort: Short,
          binaryHash: ByteVector,
          binaryPath: Path,
          genesisPath: Path): EitherT[F, Throwable, String] = {
    val containerId = for {
      cmd <- dockerCmd(name, peer, rpcPort, binaryHash, binaryPath, genesisPath)
      _ <- log(s"$name got dockerCmd")
      idPromise <- Deferred[F, Either[Throwable, String]]
      _ <- Concurrent[F].start(
        IO(cmd.!!).attempt
          .onError {
            case e => IO(println(s"Failed to run container $name: $e"))
          }
          .to[F]
          .flatMap(
            _.fold(
              e =>
                idPromise.complete(
                  new Exception(s"$name failed to start docker container: $e",
                                e).asLeft),
              id => idPromise.complete(id.asRight)
            )
          )
      )
      _ <- log(s"$name signaled docker container to start")
      containerId <- idPromise.get
      _ <- containerId.fold(
        e => log(s"$name failed to start docker container: $e"),
        id => log(s"$name docker container started $id"))
    } yield containerId

    EitherT(containerId)
  }

  def streamFileLog(
      containerId: String): EitherT[F, Throwable, fs2.Stream[F, String]] =
    for {
      logPath <- getLogPath(containerId)
      _ = println(s"$containerId logPath: $logPath")
      stream = FileStream.stream[F](logPath)
    } yield stream

  def streamLog(
      containerId: String): EitherT[F, Throwable, fs2.Stream[F, String]] = {
    val errors = scala.collection.mutable.ArrayBuffer.empty[String]
    for {
      lines <- EitherT(
        IO(s"docker logs -f $containerId".lineStream(new ProcessLogger {
          override def out(s: => String): Unit = {}

          override def err(s: => String): Unit = errors += s

          override def buffer[T](f: => T): T = f
        })).attempt.to[F])
      stream = fs2.Stream.fromIterator(lines.iterator) ++ fs2.Stream.emits(
        errors)
    } yield stream
  }

  def setLastPort(port: Short): F[Unit] = lastPort.update(cp => math.max(cp, port).toShort)
}

object Runner {
  def make[F[_]: Monad: LiftIO: ContextShift: Defer: Concurrent]: F[Runner[F]] =
    Ref.of[F, Short](30000).map(new Runner(_))
}
