package hackhack

import java.nio.file.{Path, StandardOpenOption}
import java.util.concurrent.Executors

import cats.effect.{Concurrent, ContextShift, Resource, Sync}
import cats.syntax.functor._
import fs2.io.Watcher
import fs2.{Pull, text}

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.language.higherKinds

class FileStream[F[_]: Sync](
    path: Path,
    blocking: ExecutionContextExecutor) {

  private val ChunkSize = 4096

  def stream: fs2.Stream[F, String] = {
    val events = fs2.io.file.watch(path, Seq(Watcher.EventType.Modified))

    fs2.Stream
      .eval(fileSize)
      .flatMap(size =>
        events.evalScan(0L -> size) {
          case ((_, offset), _) => fileSize.map(offset -> _)
      })
      .flatMap { case (start, end) => readRange(start, end) }
  }

  private def readRange(start: Long, end: Long) =
    fs2.io.file
      .readRange[F](path, blocking, ChunkSize, start, end)
      .through(text.utf8Decode)
      .through(text.lines)

  private def fileSize =
    fs2.io.file.pulls
      .fromPath[F](path, blocking, Seq(StandardOpenOption.READ))
      .flatMap(c => Pull.eval(c.resource.size).flatMap(Pull.output1))
      .stream
      .compile
      .toList
      .map(_.head)
}

object FileStream {
  def stream[F[_]: Sync: ContextShift: Concurrent](
      path: Path): fs2.Stream[F, String] = {
    val blocking: Resource[F, ExecutionContextExecutor] =
      Resource
        .make(
          Sync[F].delay(Executors.newCachedThreadPool())
        )(tp => Sync[F].delay(tp.shutdown()))
        .map(ExecutionContext.fromExecutor)

    fs2.Stream
      .resource(blocking)
      .flatMap(b => new FileStream[F](path, b).stream)
  }
}
