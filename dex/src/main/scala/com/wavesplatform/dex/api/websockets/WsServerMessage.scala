package com.wavesplatform.dex.api.websockets

import play.api.libs.json._

trait WsServerMessage extends WsMessage {
  val timestamp: Long
}

object WsServerMessage {
  // Will be never propagated to a client
  case object Complete extends WsServerMessage {
    override val tpe: String     = "unused"
    override val timestamp: Long = 0L
  }

  // TODO play-json discriminator
  implicit val wsServerMessageReads: Reads[WsServerMessage] = Reads { json =>
    (json \ "T").asOpt[String].fold[JsResult[WsServerMessage]](JsError(JsPath, "Type 'T' was not specified")) {
      case WsPingOrPong.tpe   => json.validate[WsPingOrPong]
      case WsAddressState.tpe => json.validate[WsAddressState]
      case WsOrderBook.tpe    => json.validate[WsOrderBook]
      case WsError.tpe        => json.validate[WsError]
      case WsInitial.tpe      => json.validate[WsInitial]
      case x                  => JsError(JsPath \ "T", s"An unknown type: $x")
    }
  }

  val wsServerMessageWrites: Writes[WsServerMessage] = Writes {
    case x: WsPingOrPong   => Json.toJson(x)
    case x: WsAddressState => Json.toJson(x)
    case x: WsOrderBook    => Json.toJson(x)
    case x: WsError        => Json.toJson(x)
    case x: WsInitial      => Json.toJson(x)
    case x                 => throw new NotImplementedError(x.getClass.getName)
  }
}
