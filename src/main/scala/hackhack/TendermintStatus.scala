package hackhack

import hackhack.TendermintStatus.{NodeInfo, SyncInfo, ValidatorInfo}
import io.circe.generic.extras.Configuration
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}

case class TendermintStatus(node_info: NodeInfo,
                            sync_info: SyncInfo,
                            validator_info: ValidatorInfo)

object TendermintStatus {
  case class ProtocolVersion(p2p: String, block: String, app: String)
  case class OtherInfo(tx_index: String, rpc_address: String)

  case class NodeInfo(
      id: String,
      listen_addr: String,
      network: String,
      version: String,
      channels: String,
      moniker: String,
      other: OtherInfo,
      protocol_version: ProtocolVersion
  )

  case class SyncInfo(
      latest_block_hash: String,
      latest_app_hash: String,
      latest_block_height: Long,
      latest_block_time: String,
      catching_up: Boolean
  )

  case class PubKey(`type`: String, value: String)

  case class ValidatorInfo(address: String,
                           pub_key: PubKey,
                           voting_power: String)

  private implicit val configuration: Configuration =
    Configuration.default.withSnakeCaseMemberNames.withSnakeCaseConstructorNames

  implicit val decodeProtocolVersion: Decoder[ProtocolVersion] = deriveDecoder
  implicit val decodeOtherInfo: Decoder[OtherInfo] = deriveDecoder
  implicit val decodeNodeInfo: Decoder[NodeInfo] = deriveDecoder
  implicit val decodeSyncInfo: Decoder[SyncInfo] = deriveDecoder
  implicit val decodePubKey: Decoder[PubKey] = deriveDecoder
  implicit val decodeValidatorInfo: Decoder[ValidatorInfo] = deriveDecoder
  implicit val decodeCheck: Decoder[TendermintStatus] = deriveDecoder

  implicit val encodeProtocolVersion: Encoder[ProtocolVersion] = deriveEncoder
  implicit val encodeOtherInfo: Encoder[OtherInfo] = deriveEncoder
  implicit val encodeNodeInfo: Encoder[NodeInfo] = deriveEncoder
  implicit val encodeSyncInfo: Encoder[SyncInfo] = deriveEncoder
  implicit val encodePubKey: Encoder[PubKey] = deriveEncoder
  implicit val encodeValidatorInfo: Encoder[ValidatorInfo] = deriveEncoder
  implicit val encodeCheck: Encoder[TendermintStatus] = deriveEncoder
}
