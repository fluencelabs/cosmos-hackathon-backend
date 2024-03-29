package hackhack.docker

import java.time.Instant
import java.util.concurrent.{ExecutorService, Executors}

import cats.data.EitherT
import cats.effect._
import cats.syntax.applicativeError._
import cats.syntax.functor._
import cats.{Defer, Monad, ~>}
import hackhack.docker.params.DockerParams

import scala.concurrent.ExecutionContext
import scala.language.higherKinds
import scala.sys.process._
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

class DockerIO[F[_]: Monad: LiftIO: ContextShift: Defer](
    ctx: ExecutionContext,
    defaultStopTimeout: Int
) {

  private val liftCtx: IO ~> F = new (IO ~> F) {
    override def apply[A](fa: IO[A]): F[A] =
      ContextShift[F].evalOn(ctx)(fa.to[F])
  }

  /**
    * Run shell command
    */
  private def runShell(cmd: String): EitherT[IO, DockerError, String] =
    IO {
      println(s"Running Docker command: `$cmd`")
      cmd.!!.trim
    }.attemptT.leftMap(DockerCommandError(cmd, _))

  /**
    *  Run shell command and raise error on non-zero exit code. Command response is dropped
    */
  private def runShellVoid(cmd: String): IO[Unit] = {
    IO(cmd.!).flatMap {
      case exit if exit != 0 =>
        println(s"`$cmd` exited with code: $exit")
        IO.raiseError[Unit](new Exception(s"`$cmd` exited with code: $exit"))
      case _ => IO.unit
    }
  }

  private def getNameIO(containerId: String): EitherT[IO, DockerError, String] =
    runShell(s"""docker ps -af id=$containerId --format "{{.Names}}" """)
      .map(_.trim.replace("\"", ""))

  /**
    * Get Docker container's name, if it's possible
    *
    * @param container Docker Container
    */
  def getName(container: DockerContainer): EitherT[F, DockerError, String] =
    getNameIO(container.containerId)
      .mapK(liftCtx)

  /**
    * Runs a temporary docker container with custom executable. Returns stdout of execution as a string.
    * Caller is responsible for container removal.
    *
    * @param params parameters for Docker container
    * @return a string with execution stdout
    */
  def exec(params: DockerParams.ExecParams): EitherT[F, DockerError, String] =
    IO {
      println(s"Executing docker command: ${params.command.mkString(" ")}")
      params.process.!!
    }.map(_.trim)
      .attemptT
      .leftMap[DockerError](DockerCommandError(params.command.mkString(" "), _))
      .mapK(liftCtx)

  /**
    * Runs a daemonized docker container, providing a single String with the container ID.
    * Calls `docker rm -f` on that ID when stream is over.
    *
    * @param params parameters for Docker container, must start with `docker run -d`
    * @param stopTimeout Container clean up timeout: SIGTERM is sent, and if container is still alive after timeout, SIGKILL produced
    * @return a stream that produces a docker container ID
    */
  def run(
      params: DockerParams.DaemonParams,
      stopTimeout: Int = defaultStopTimeout): Resource[F, DockerContainer] = {
    val runContainer: IO[Either[Throwable, String]] =
      IO {
        println(s"Running docker daemon: ${params.command.mkString(" ")}")
        params.process.!!
      }.map(_.trim)
        .attemptT
        .leftMap { err ⇒
          println("Cannot run docker container: " + err, err)
          err
        }
        .value

    def tryStopContainer(name: String,
                         dockerId: String,
                         exitCase: ExitCase[Throwable]): IO[Try[Int]] =
      IO {
        println(
          s"Going to stop container $name $dockerId, exit case: $exitCase")
        val t = Try(s"docker stop -t $stopTimeout $dockerId".!)
        // TODO should we `docker kill` if Cancel is triggered while stopping?
        println(s"Stop result: $t")
        t
      }

    def rmOnGracefulStop(name: String, dockerId: String): IO[Unit] =
      IO {
        println(
          s"Container $dockerId with name $name stopped gracefully, going to rm -v it")
        val containerLogs =
          s"docker logs --tail 100 $dockerId".!!.replaceAll("(?m)^", s"$name  ")
        if (containerLogs.trim.nonEmpty)
          println(Console.CYAN + containerLogs + Console.RESET)
        else
          println(Console.CYAN + s"$name: empty logs." + Console.RESET)
        s"docker rm -v $dockerId".!
      }.void

    def forceRmWhenCannotStop(name: String,
                              dockerId: String,
                              err: Throwable): IO[Unit] =
      IO {
        println(
          s"Stopping docker container $name $dockerId errored due to $err, going to rm -v -f it",
          err)
        s"docker rm -v -f $dockerId".!
      }.void

    def forceRmWhenStopNonZero(name: String,
                               dockerId: String,
                               code: Int): IO[Unit] =
      IO {
        println(
          s"Stopping docker container $name $dockerId failed, exit code = $code, going to rm -v -f it")
        s"docker rm -v -f $dockerId".!
      }.void

    Resource
      .makeCase(runContainer) {
        case (Right(dockerId), exitCase) ⇒
          getNameIO(dockerId)
            .getOrElse("(name is unknown)")
            .flatMap { name ⇒
              tryStopContainer(name, dockerId, exitCase).flatMap {
                case Success(0) ⇒
                  rmOnGracefulStop(name, dockerId)

                case Failure(err) ⇒
                  forceRmWhenCannotStop(name, dockerId, err)

                case Success(x) ⇒
                  forceRmWhenStopNonZero(name, dockerId, x)
              }
            }
            .handleError { err ⇒
              println(s"Error cleaning up container $dockerId: $err", err)
            }

        case (Left(err), _) ⇒
          println(
            s"Cannot cleanup the docker container as it's failed to launch: $err",
            err)
          IO.unit
      }
      .flatMap[DockerContainer] {
        case Right(dockerId) ⇒
          Resource.pure(DockerContainer(dockerId))
        case Left(err) ⇒
          println(s"Resource cannot be acquired, error raised $err", err)
          Resource.liftF[IO, DockerContainer](IO.raiseError(err))
      }
      .mapK(liftCtx)
  }

  /**
    * Inspect the docker container to find out its running status
    *
    * @param container Docker container
    * @return DockerRunning or any error found on the way
    */
  def checkContainer(
      container: DockerContainer): EitherT[F, DockerError, DockerRunning] = {
    import java.time.format.DateTimeFormatter
    val format = DateTimeFormatter.ISO_DATE_TIME
    val dockerId = container.containerId
    for {
      status ← runShell(
        s"docker inspect -f {{.State.Running}},{{.State.StartedAt}} $dockerId")
      timeIsRunning ← IO {
        val running :: started :: Nil = status.trim.split(',').toList

        // TODO get any reason of why container is stopped
        println(
          s"Docker container $dockerId  status = [$running], startedAt = [$started]")

        Instant.from(format.parse(started)).getEpochSecond → running.contains(
          "true")
      }.attemptT.leftMap(DockerException(
        s"Cannot parse container status: $status",
        _): DockerError)
    } yield timeIsRunning
  }.mapK(liftCtx).subflatMap {
    case (time, true) ⇒ Right(DockerRunning(time))
    case (time, false) ⇒ Left(DockerContainerStopped(time))
  }

  /**
    *  Create docker network as a resource. Network is deleted after resource is used.
    */
  def makeNetwork(name: String): Resource[F, DockerNetwork] =
    Resource
      .make(
        runShellVoid(s"docker network create $name").as(DockerNetwork(name))) {
        case DockerNetwork(n) =>
          IO(println(s"removing network $n"))
            .flatMap(_ => runShellVoid(s"docker network rm $n"))
            .handleError {
              case NonFatal(err) ⇒
                println(s"Trying to remove network $n, got error $err", err)
            }
      }
      .mapK(liftCtx)

  /**
    * Join (connect to) docker network as a resource. Container will be disconnected from network after resource is used.
    */
  def joinNetwork(container: DockerContainer,
                  network: DockerNetwork): Resource[F, Unit] =
    Resource
      .make(
        runShellVoid(
          s"docker network connect ${network.name} ${container.containerId}"))(
        _ =>
          IO(println(
            s"disconnecting container ${container.containerId} from network ${network.name} "))
            .flatMap(_ =>
              runShellVoid(
                s"docker network disconnect ${network.name} ${container.containerId}"))
            .handleError {
              case NonFatal(err) ⇒
                println(
                  s"Trying to disconnect container ${container.containerId}" +
                    s"from network ${network.name}, got error $err"
                )
          }
      )
      .mapK(liftCtx)
}

object DockerIO {

  def apply[F[_]](implicit dio: DockerIO[F]): DockerIO[F] = dio

  def make[F[_]: Monad: LiftIO: ContextShift: Defer](
      ex: ⇒ ExecutorService = Executors.newSingleThreadExecutor(),
      defaultStopTimeout: Int = 10
  ): Resource[F, DockerIO[F]] =
    Resource
      .make(IO(ExecutionContext.fromExecutorService(ex)).to[F])(
        ctx ⇒ IO(ctx.shutdown()).to[F]
      )
      .map(ctx ⇒ new DockerIO[F](ctx, defaultStopTimeout))
}
