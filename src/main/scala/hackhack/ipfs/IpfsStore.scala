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

import cats.{Functor, Monad}
import cats.data.EitherT
import com.softwaremill.sttp.{SttpBackend, Uri}
import scodec.bits.ByteVector

import scala.language.higherKinds

/**
  * Implementation of IPFS downloading mechanism
  *
  * @param client to interact with IPFS nodes
  */
class IpfsStore[F[_]: Functor](client: IpfsClient[F]) {
  def fetch(
      hash: ByteVector): EitherT[F, IpfsError, fs2.Stream[F, ByteBuffer]] =
    client.download(hash).leftMap(e => IpfsError("fetch", Some(e)))
}

object IpfsStore {

  def apply[F[_]](
      address: Uri
  )(implicit F: Monad[F],
    sttpBackend: SttpBackend[EitherT[F, Throwable, ?],
                             fs2.Stream[F, ByteBuffer]]): IpfsStore[F] =
    new IpfsStore(new IpfsClient[F](address))
}
