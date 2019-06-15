package hackhack

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}

import scala.util.Try
import cats.syntax.option._

case class Log(appName: String,
               height: Option[Long],
               hash: String,
               expectedHash: Option[String],
               correct: Boolean)

object Log {
  /*
I[2019-06-13|01:07:31.417] Committed state                              module=state height=7096 txs=1 appHash=4A936D7C00A37D66C7CE38A1118E0BC31C97BEE68C70BA8A9365EE0126079DAE
E[2019-06-13|01:07:33.678] CONSENSUS FAILURE!!!                         module=consensus err="Panicked on a Consensus Failure: +2/3 committed an invalid block: Wrong Block.Header.AppHash.  Expected 4A936D7C00A37D66C7CE38A1118E0BC31C97BEE68C70BA8A9365EE0126079DAE, got 0113492DE4944A90AB6AB3B64D04EAB2D9257D8613FABCACB3CB341CAF79D490"
   */

  private val commitRegex =
    """(?s).*?Committed state\s+module=state height=(\d+) txs=\d+ appHash=(\S+).*""".r
  private val consensusFailedRegex =
    """(?s).*?CONSENSUS FAILURE.*?Expected ([A-Z0-9]+), got ([A-Z0-9]+).*""".r

  def apply(appName: String, line: String): Option[Log] = line match {
    case commitRegex(height, hash) =>
      new Log(appName, Try(height.toLong).toOption, hash, None, true).some
    case consensusFailedRegex(expected, got) =>
      new Log(appName, None, got, Some(expected), false).some
    case str =>
      println(s"skipping $str")
      None
  }

  implicit val encodeLog: Encoder[Log] = deriveEncoder
  implicit val decodeLog: Decoder[Log] = deriveDecoder
}
