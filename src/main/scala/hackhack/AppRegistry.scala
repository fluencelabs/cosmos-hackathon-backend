package hackhack

import java.nio.ByteBuffer
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.Executors

import cats.{Monad, Traverse}
import cats.data.EitherT
import cats.effect._
import cats.syntax.functor._
import cats.syntax.applicative._
import cats.instances.list._
import cats.syntax.flatMap._
import cats.syntax.applicative._
import cats.effect.concurrent.{Deferred, Ref}
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.option._
import com.softwaremill.sttp.{SttpBackend, Uri, sttp}
import hackhack.ipfs.{IpfsError, IpfsStore}
import io.circe.Json
import io.circe.parser.parse
import scodec.bits.ByteVector

import scala.concurrent.ExecutionContext
import scala.language.higherKinds

case class Peer(
    host: String,
    rpcPort: Short,
) {
  val RpcUri = Uri(host, rpcPort)
}

case class App(name: String,
               containerId: String,
               peer: Peer,
               binaryHash: ByteVector,
               binaryPath: Path)

class AppRegistry[F[_]: Monad: Concurrent: ContextShift: Timer: LiftIO](
    ipfsStore: IpfsStore[F],
    runner: Runner[F],
    apps: Ref[F, Map[String, Deferred[F, App]]],
    blockingCtx: ExecutionContext =
      ExecutionContext.fromExecutorService(Executors.newCachedThreadPool()))(
    implicit sttpBackend: SttpBackend[EitherT[F, Throwable, ?],
                                      fs2.Stream[F, ByteBuffer]]) {

  private def putApp(app: App) =
    for {
      d <- Deferred[F, App]
      _ <- d.complete(app)
      _ <- apps.update(
        map =>
          map
            .get(app.name)
            .fold(map.updated(app.name, d))(_ => map))
    } yield ()

  def loadExistingContainers(): EitherT[F, Throwable, Unit] = {
    (for {
      existingApps <- runner.listFishermenContainers
      _ = existingApps.foreach(a => println(s"Existing app: $a"))
      _ <- EitherT.liftF[F, Throwable, Unit] {
        Traverse[List]
          .sequence(existingApps.map(putApp))
          .void
      }
    } yield ()).leftMap { e =>
      println(s"Error loadExistingContainers: $e")
      new Exception(s"Error on loading existing containers: $e", e)
    }
  }

  def stream(name: String): EitherT[F, Throwable, fs2.Stream[F, Log]] =
    for {
      app <- EitherT(getApp(name))
      stream <- runner.streamLog(app.containerId)
    } yield
      stream
        .evalTap(line => Sync[F].delay(println(s"line $name $line")))
        .map(Log(name, _))
        .unNone
        .evalTap(log => Sync[F].delay(println(s"log $name $log")))

  // Returns consensusHeight
  def run(name: String, peer: Peer, hash: String): EitherT[F, Throwable, Long] =
    for {
      deferred <- EitherT.liftF(Deferred[F, App])
      _ <- EitherT(
        apps.modify(
          map =>
            map
              .get(name)
              .fold(map.updated(name, deferred) -> ().asRight[Throwable])(_ =>
                map ->
                  new Exception(s"app $name was already started").asLeft[Unit]))
      )

      genesis <- dumpGenesis(name, peer)
      _ <- log(s"$name dumped genesis")

      baseDir <- EitherT(
        IO(
          Paths
            .get(System.getProperty("user.home"), s".salmon/$name")
            .toAbsolutePath).attempt.to[F])
      _ <- EitherT(IO(Files.createDirectories(baseDir)).attempt.to[F])
      genesisPath = baseDir.resolve("genesis.json")
      _ <- EitherT(
        IO(Files.write(genesisPath, genesis.getBytes())).attempt.to[F])
      _ <- log(s"$name saved genesis -> $genesisPath")

      binaryHash <- EitherT.fromEither[F](
        ByteVector
          .fromBase58Descriptive(hash)
          .map(_.drop(2))
          .leftMap(e =>
            new Exception(s"Failed to decode binary hash from base64: $e"): Throwable))

      binaryPath = baseDir.resolve("binary")
      _ <- fetchTo(binaryHash, binaryPath).leftMap(identity[Throwable])
      _ <- log(s"$name binary downloaded $binaryPath")

      status <- status(name, peer)
      _ <- log(s"$name got peer status")

      containerId <- runner.run(name,
                                p2pPeer(peer, status),
                                peer.rpcPort,
                                binaryHash,
                                binaryPath,
                                genesisPath)
      _ <- log(s"$name container started $containerId")

      app = App(name, containerId, peer, binaryHash, binaryPath)
      _ <- EitherT.liftF(deferred.complete(app))
    } yield status.sync_info.latest_block_height

  private def log(str: String) = EitherT(IO(println(str)).attempt.to[F])

  private def getApp(name: String): F[Either[Throwable, App]] =
    for {
      map <- apps.get
      appOpt = map.get(name)
      app <- appOpt.fold(
        new Exception(s"There is no app $name").asLeft[App].pure[F])(
        _.get.map(_.asRight))
    } yield app

  private def status(appName: String,
                     peer: Peer): EitherT[F, Throwable, TendermintStatus] = {
    rpc(appName, peer, "/status").subflatMap(
      _.hcursor
        .downField("result")
        .as[TendermintStatus]
    )
  }

  private def p2pPeer(peer: Peer, status: TendermintStatus) = {
    val id = status.node_info.id
    val port =
      status.node_info.listen_addr.replace("tcp://", "").split(":").tail.head
    s"$id@${peer.host}:$port"
  }

  private def dumpGenesis(appName: String,
                          peer: Peer): EitherT[F, Throwable, String] = {
    rpc(appName, peer, "/genesis").subflatMap { json =>
      json.hcursor.downField("result").get[Json]("genesis").map(_.spaces2)
    }
  }

  private def rpc(appName: String,
                  peer: Peer,
                  path: String): EitherT[F, Throwable, Json] =
    Backoff.default.retry(
      sttp
        .get(peer.RpcUri.path(path))
        .send[EitherT[F, Throwable, ?]]
        .subflatMap(
          _.body
            .leftMap(e =>
              new Exception(s"Error RPC $path $appName: ${peer.RpcUri}: $e"))
            .flatMap(s => parse(s))
        ),
      (e: Throwable) =>
        Monad[F].pure(println(s"Error RPC $path $appName: ${peer.RpcUri}: $e")),
      max = 10
    )

  private def fetchTo(hash: ByteVector,
                      dest: Path): EitherT[F, IpfsError, Unit] = {
    ipfsStore
      .fetch(hash)
      .flatMap(
        _.flatMap(bb ⇒ fs2.Stream.chunk(fs2.Chunk.byteBuffer(bb)))
          .through(fs2.io.file.writeAll[F](dest, blockingCtx))
          .compile
          .drain
          .attemptT
          .leftMap(e => IpfsError("fetchTo", e.some))
      )
  }
}

object AppRegistry {
  def make[F[_]: Monad: Concurrent: ContextShift: Timer: LiftIO](
      ipfsStore: IpfsStore[F],
      runner: Runner[F])(
      implicit sttpBackend: SttpBackend[EitherT[F, Throwable, ?],
                                        fs2.Stream[F, ByteBuffer]])
    : F[AppRegistry[F]] =
    for {
      ref <- Ref.of[F, Map[String, Deferred[F, App]]](Map.empty)
    } yield new AppRegistry[F](ipfsStore, runner, ref)
}
