package hackhack

import java.nio.ByteBuffer
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.Executors

import cats.Monad
import cats.data.EitherT
import cats.effect._
import cats.effect.concurrent.Ref
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
    p2pPort: Short,
    rpcPort: Short,
    p2pKey: String
) {
  val RpcUri = Uri(s"http://$host:$rpcPort")
  val P2pSeed = s"$p2pKey@$host:$p2pPort"
}

case class App(name: String,
               containerId: String,
               rpcPort: Short,
               seed: String,
               binaryHash: ByteVector,
               binaryPath: Path)

class AppRegistry[F[_]: Monad: Concurrent: ContextShift: Timer: LiftIO](
    ipfsStore: IpfsStore[F],
    runner: Runner[F],
    apps: Ref[F, Map[String, App]],
    blockingCtx: ExecutionContext =
      ExecutionContext.fromExecutorService(Executors.newCachedThreadPool()))(
    implicit sttpBackend: SttpBackend[EitherT[F, Throwable, ?],
                                      fs2.Stream[F, ByteBuffer]]) {

  def log(str: String) = EitherT(IO(println(str)).attempt.to[F])

  // Returns consensusHeight
  def run(name: String,
          peer: Peer,
          hash: ByteVector): EitherT[F, Throwable, Long] =
    for {
      genesis <- dumpGenesis(name, peer)
      _ <- log(s"$name dumped genesis")
      baseDir <- EitherT(
        IO(
          Paths
            .get(System.getProperty("user.home"), s".salmon/$name")
            .toAbsolutePath).attempt.to[F])
      _ <- EitherT(IO(baseDir.toFile.mkdir()).attempt.to[F])
      path = baseDir.resolve("genesis.json")
      _ <- EitherT(IO(Files.write(path, genesis.getBytes())).attempt.to[F])
      _ <- log(s"$name saved genesis")
      binaryPath = baseDir.resolve("binary")
      _ <- fetchTo(hash, binaryPath).leftMap(identity[Throwable])
      _ <- log(s"$name binary downloaded $binaryPath")
      height <- consensusHeight(name, peer)
    } yield height

  def consensusHeight(appName: String,
                      peer: Peer): EitherT[F, Throwable, Long] = {
    rpc(appName, peer, "/status").subflatMap(
      _.hcursor
        .downField("result")
        .downField("sync_info")
        .get[Long]("latest_block_height")
    )
  }

  def dumpGenesis(appName: String,
                  peer: Peer): EitherT[F, Throwable, String] = {
    rpc(appName, peer, "/genesis").subflatMap(
      _.hcursor.downField("result").get[String]("genesis"))
  }

  def rpc(appName: String,
          peer: Peer,
          path: String): EitherT[F, Throwable, Json] = Backoff.default.retry(
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

  def fetchTo(hash: ByteVector, dest: Path): EitherT[F, IpfsError, Unit] = {
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
