package hackhack

import scala.language.higherKinds

case class Log(height: Long, hash: String, expectedHash: Option[String], correct: Boolean)

class LogParser[F[_]] {
  def stream: fs2.Stream[F, Log] = ???

  private def retrieveLogs = ???
}
