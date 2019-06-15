package hackhack

import cats.{Applicative, Functor, Monad}
import cats.data.EitherT
import cats.effect.Timer
import cats.syntax.flatMap._
import cats.syntax.apply._
import cats.syntax.applicative._
import cats.syntax.either._
import cats.instances.either._
import cats.effect.syntax.all._

import scala.concurrent.duration._
import scala.language.higherKinds

/**
  * Exponential backoff delays.
  *
  * @param delayPeriod will be applied next time
  * @param maxDelay upper bound for a single delay
  */
case class Backoff[E](delayPeriod: FiniteDuration, maxDelay: FiniteDuration) {

  /**
    * Next retry policy with delayPeriod multiplied times two, if maxDelay is not yet reached
    */
  def next: Backoff[E] =
    if (delayPeriod == maxDelay) this
    else {
      val nextDelay = delayPeriod * 2
      if (nextDelay > maxDelay) copy(delayPeriod = maxDelay)
      else copy(delayPeriod = nextDelay)
    }

  def retry[F[_]: Timer: Monad: Functor, EE <: E, T](
      fn: EitherT[F, EE, T],
      onError: EE ⇒ F[Unit],
      max: Int): EitherT[F, EE, T] =
    fn.biflatMap(
      err =>
        if (max == 0) EitherT(err.asLeft.pure[F])
        else
          EitherT
            .right[EE](onError(err) *> Timer[F].sleep(delayPeriod))
            .flatMap(_ => next.retry(fn, onError, max - 1)),
      EitherT.pure(_)
    )

  def retryForever[F[_]: Timer: Monad, EE <: E, T](
      fn: EitherT[F, EE, T],
      onError: EE ⇒ F[Unit]): F[T] =
    fn.value.flatMap {
      case Right(value) ⇒ Applicative[F].pure(value)
      case Left(err) ⇒
        onError(err) *> Timer[F].sleep(delayPeriod) *> next.retryForever(
          fn,
          onError)
    }

  def apply[F[_]: Timer: Monad, EE <: E, T](fn: EitherT[F, EE, T]): F[T] =
    retryForever(fn, (_: EE) ⇒ Applicative[F].unit)

}

object Backoff {
  def default[E]: Backoff[E] = Backoff(1.second, 1.minute)
}
