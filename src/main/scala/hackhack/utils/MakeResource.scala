package hackhack.utils

import cats.effect.concurrent.Deferred
import cats.effect.{Concurrent, Fiber, Resource}
import cats.syntax.flatMap._
import cats.syntax.functor._

import scala.language.higherKinds

object MakeResource {
  /**
   * Uses the resource concurrently in a separate fiber, until the given F[Unit] resolves.
   *
   * @param resource release use => resource
   * @tparam F Effect
   * @return Delayed action of using the resource
   */
  def useConcurrently[F[_]: Concurrent](resource: F[Unit] ⇒ Resource[F, _]): F[Unit] =
    for {
      completeDef ← Deferred[F, Unit]
      fiberDef ← Deferred[F, Fiber[F, Unit]]
      fiber ← Concurrent[F].start(
        resource(
          completeDef.complete(()) >> fiberDef.get.flatMap(_.join)
        ).use(_ ⇒ completeDef.get)
      )
      _ ← fiberDef.complete(fiber)
    } yield ()
}
