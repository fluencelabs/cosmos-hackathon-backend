package hackhack

import java.nio.file.{Path, Paths}
import java.util.concurrent.Executors

import cats.data.EitherT
import cats.effect._
import cats.effect.concurrent.Ref
import cats.syntax.applicativeError._
import cats.syntax.option._
import cats.syntax.functor._
import cats.{Defer, Monad}
import hackhack.docker.params.{DockerImage, DockerParams}
import hackhack.ipfs.{IpfsError, IpfsStore}
import scodec.bits.ByteVector

import scala.concurrent.ExecutionContext
import scala.language.{higherKinds, postfixOps}

case class Runner[F[_]: Monad: LiftIO: ContextShift: Defer: Concurrent](
    ipfsStore: IpfsStore[F],
    lastPort: Ref[F, Short],
    blockingCtx: ExecutionContext =
      ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
) {

//  nsd --log_level "debug" start --moniker stage-02 --address tcp://0.0.0.0:26658 --p2p.laddr tcp://0.0.0.0:26656 --rpc.laddr tcp://0.0.0.0:26657 --p2p.persistent_peers d53cf2cb91514edb41441503e8a11f004023f2ee@207.154.210.151:26656

  private def nextPortThousand =
    lastPort.modify(p => (p + 1 toShort, p * 1000 toShort))

  private def dockerCmd(image: String,
                        name: String,
                        peer: String,
                        binaryPath: Path) =
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
        .prepared(DockerImage(image, "latest"))
        .daemonRun()
        .process
    } yield process

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

  def run(image: String, name: String, peer: String, ipfsHash: ByteVector) =
//    for {
//      path <- EitherT(IO(Paths.get(s"/tmp/$name")).attempt.to[F])
//      binary <- fetchTo(ipfsHash, path)
//      container <- EitherT.liftF(
//        Concurrent[F].start(IO(dockerCmd(image, name, peer, path)).to[F]))
//    } yield ???
  ???

}
