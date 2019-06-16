/*
 * Copyright 2018 Fluence Labs Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package hackhack.ipfs

import java.nio.ByteBuffer

import cats.Traverse.ops._
import cats.data.EitherT
import cats.instances.either._
import cats.instances.list._
import cats.syntax.either._
import cats.{Applicative, Monad}
import com.softwaremill.sttp.Uri.QueryFragment.KeyValue
import com.softwaremill.sttp.circe.asJson
import com.softwaremill.sttp.{Multipart, SttpBackend, Uri, asStream, sttp, _}
import fs2.RaiseThrowable
import io.circe.{Decoder, DecodingFailure}
import scodec.bits.ByteVector

import scala.collection.immutable
import scala.language.higherKinds

// TODO move somewhere else
object ResponseOps {
  import cats.data.EitherT
  import com.softwaremill.sttp.Response

  implicit class RichResponse[F[_], T, EE <: Throwable](
      resp: EitherT[F, Throwable, Response[T]])(
      implicit F: Monad[F]
  ) {
    val toEitherT: EitherT[F, String, T] =
      resp.leftMap(_.getMessage).subflatMap(_.body)
    def toEitherT[E](errFunc: String => E): EitherT[F, E, T] =
      toEitherT.leftMap(errFunc)
  }
}

object Multihash {
  // https://github.com/multiformats/multicodec/blob/master/table.csv
  val SHA256 = ByteVector(0x12, 32) // 0x12 => SHA256; 32 = 256 bits in bytes
  def asBase58(hash: ByteVector): String = (SHA256 ++ hash).toBase58
}

class IpfsClient[F[_]: Monad](ipfsUri: Uri)(
    implicit sttpBackend: SttpBackend[EitherT[F, Throwable, ?],
                                      fs2.Stream[F, ByteBuffer]]
) {

  import IpfsClient._
  import IpfsLsResponse._
  import ResponseOps._

  // URI for downloading data
  private val CatUri = ipfsUri.path("/api/v0/cat")

  // URI for listing data if it has nested resources
  private val LsUri = ipfsUri.path("/api/v0/ls")

  private val UploadUri = ipfsUri.path("/api/v0/add")

  // Converts 256-bits hash to a base58 IPFS address, prepending multihash bytes
  private def toAddress(hash: ByteVector): String =
    (Multihash.SHA256 ++ hash).toBase58

  // Converts base58 IPFS address to a 256-bits hash
  private def fromAddress(str: String): Either[String, ByteVector] =
    ByteVector.fromBase58Descriptive(str).map(_.drop(2))

  /**
    * Downloads data from IPFS.
    *
    * @param hash data address in IPFS
    * @return
    */
  def download(
      hash: ByteVector): EitherT[F, Throwable, fs2.Stream[F, ByteBuffer]] = {
//    implicit val wtf = sttpBackend
    val address = toAddress(hash)
    val uri = CatUri.param("arg", address)
    for {
      _ <- EitherT.pure[F, Throwable](println(s"IPFS 'download' started $uri"))
      response <- sttp
        .response(asStream[fs2.Stream[F, ByteBuffer]])
        .get(uri)
        .send[EitherT[F, Throwable, ?]]()
        .toEitherT { er =>
          val errorMessage = s"IPFS 'download' error $uri: $er"
          IpfsError(errorMessage)
        }
        .map { r =>
          println(s"IPFS 'download' finished $uri")
          r
        }
        .leftMap(identity[Throwable])
    } yield response
  }
}

object IpfsClient {
  import io.circe.fs2.stringStreamParser

  def assert[F[_]: Applicative](
      test: Boolean,
      errorMessage: String): EitherT[F, IpfsError, Unit] =
    EitherT.fromEither[F](Either.cond(test, (), IpfsError(errorMessage)))

  // parses application/json+stream like {object1}\n{object2}...
  def asListJson[B: Decoder: IsOption]
    : ResponseAs[Decoder.Result[List[B]], Nothing] = {
    implicit val rt = new RaiseThrowable[fs2.Pure] {}
    asString
      .map(fs2.Stream.emit)
      .map(
        _.through(stringStreamParser[fs2.Pure]).attempt
          .map(_.leftMap {
            case e: DecodingFailure => e
            case e: Throwable       => DecodingFailure(e.getLocalizedMessage, Nil)
          })
          .toList
          .map(_.map(_.as[B]).flatMap(identity))
          .sequence[Either[DecodingFailure, ?], B]
      )
  }
}
