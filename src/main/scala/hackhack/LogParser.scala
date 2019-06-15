package hackhack

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import cats.syntax.option._

import scala.language.higherKinds
import scala.util.Try

class LogParser[F[_]] {
  def stream: fs2.Stream[F, Log] = ???

  private def retrieveLogs = ???
}
