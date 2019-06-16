package hackhack

import java.nio.ByteBuffer
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.Executors

import cats.data.EitherT
import cats.effect._
import cats.effect.concurrent.{Deferred, Ref, TryableDeferred}
import cats.instances.list._
import cats.instances.option._
import cats.instances.either._
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.{Monad, Traverse}
import com.softwaremill.sttp.{SttpBackend, Uri, sttp}
import hackhack.ipfs.{IpfsError, IpfsStore, Multihash}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.parser.parse
import io.circe.{Decoder, Encoder, Json, ObjectEncoder}
import scodec.bits.ByteVector

import scala.concurrent.ExecutionContext
import scala.language.higherKinds

case class Peer(
    host: String,
    rpcPort: Short,
) {
  val RpcUri = Uri(host, rpcPort)
}

object Peer {
  implicit val encodePeer: Encoder[Peer] = deriveEncoder
  implicit val decodePeer: Decoder[Peer] = deriveDecoder
}

case class App(name: String,
               containerId: String,
               peer: Peer,
               binaryHash: ByteVector,
               binaryPath: Path)

object App {
  private implicit val encbc: Encoder[ByteVector] =
    Encoder.encodeString.contramap(_.toHex)
  private implicit val encPath: Encoder[Path] =
    Encoder.encodeString.contramap(_.toString)
  implicit val encodeApp: ObjectEncoder[App] = deriveEncoder
}

case class AppInfo(name: String,
                   network: String,
                   binaryHash: String,
                   consensusHeight: Long,
                   validatorsCount: Int)

object AppInfo {
  private implicit val encbc: Encoder[ByteVector] =
    Encoder.encodeString.contramap(_.toHex)
  implicit val encodeAppInfo: Encoder[AppInfo] = deriveEncoder
}

class AppRegistry[F[_]: Monad: Concurrent: ContextShift: Timer: LiftIO](
    ipfsStore: IpfsStore[F],
    runner: Runner[F],
    apps: Ref[F, Map[String, TryableDeferred[F, App]]],
    blockingCtx: ExecutionContext =
      ExecutionContext.fromExecutorService(Executors.newCachedThreadPool()))(
    implicit sttpBackend: SttpBackend[EitherT[F, Throwable, ?],
                                      fs2.Stream[F, ByteBuffer]]) {

  private def putApp(app: App) =
    for {
      d <- Deferred.tryable[F, App]
      _ <- d.complete(app)
      _ <- apps.update(
        map =>
          map
            .get(app.name)
            .fold(map.updated(app.name, d))(_ => map))
    } yield ()

  def loadExistingContainers(): EitherT[F, Exception, Short] = {
    (for {
      existingApps <- runner.listFishermenContainers
      _ = existingApps.foreach(a => println(s"Existing app: $a"))
      _ <- EitherT.liftF[F, Throwable, Unit] {
        Traverse[List]
          .sequence(existingApps.map(putApp))
          .void
      }
      maxPort = existingApps.maxBy(_.peer.rpcPort).peer.rpcPort
    } yield maxPort).leftMap { e =>
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
      deferred <- EitherT.liftF(Deferred.tryable[F, App])
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
      _ <- ipfsFetch(binaryHash, binaryPath).leftMap(identity[Throwable])
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

  def getAllApps: EitherT[F, Throwable, List[AppInfo]] = {
    for {
      appList <- EitherT.right(
        apps.get.flatMap(
          map =>
            Traverse[List]
              .sequence(map.values.map(_.tryGet).toList)
              .map(_.flatten))
      )
      statuses <- Traverse[List].sequence(appList.map(app =>
        status(app.name, app.peer)))
    } yield
      appList.zip(statuses).map {
        case (app, status) =>
          AppInfo(app.name,
                  status.node_info.network,
                  Multihash.asBase58(app.binaryHash),
                  status.sync_info.latest_block_height,
                  4)
      }
  }

  def getBlock(name: String, height: Long): EitherT[F, Throwable, String] =
    for {
      appOpt <- EitherT.right(apps.get.flatMap(map =>
        Traverse[Option].sequence(map.get(name).map(_.tryGet)).map(_.flatten)))
      app <- appOpt
        .fold(new Exception(s"There is no app $name").asLeft[App])(_.asRight)
        .toEitherT[F]
      block <- rpc(name, app.peer, s"/block", "height" -> height.toString)
    } yield block.spaces2

  private def log(str: String) = EitherT(IO(println(str)).attempt.to[F])

  private def getApp(name: String): F[Either[Throwable, App]] =
    for {
      map <- apps.get
      appOpt <- Traverse[Option]
        .sequence(map.get(name).map(_.tryGet))
        .map(_.flatten)
      app = appOpt.fold(new Exception(s"There is no app $name").asLeft[App])(
        _.asRight)
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
                  path: String,
                  params: (String, String)*): EitherT[F, Throwable, Json] =
    Backoff.default.retry(
      sttp
        .get(peer.RpcUri.path(path).params(params: _*))
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

  private def ipfsFetch(hash: ByteVector,
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
      ref <- Ref.of[F, Map[String, TryableDeferred[F, App]]](Map.empty)
    } yield new AppRegistry[F](ipfsStore, runner, ref)
}
