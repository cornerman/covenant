package covenant.ws

import chameleon._
import monix.execution.Scheduler
import mycelium.client._
import mycelium.core._
import mycelium.core.message._

private[ws] trait NativeWsRequestTransport {
  def apply[PickleType, ErrorType](
    uri: String,
    config: WebsocketClientConfig = WebsocketClientConfig(),
  )(implicit
    scheduler: Scheduler,
    builder: JsMessageBuilder[PickleType],
    serializer: Serializer[ClientMessage[PickleType], PickleType],
    deserializer: Deserializer[ServerMessage[PickleType, ErrorType], PickleType]
  ) = {
    val connection = new JsWebsocketConnection
    WsRequestTransport.fromConnection(uri, connection, config)
  }
}
