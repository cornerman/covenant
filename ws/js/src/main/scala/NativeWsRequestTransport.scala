package covenant.ws

import sloth._
import covenant.core.DefaultLogHandler
import mycelium.client._
import mycelium.core._
import mycelium.core.message._
import chameleon._
import cats.data.EitherT
import monix.execution.Scheduler
import monix.reactive.Observable
import cats.implicits._

import scala.concurrent.Future

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
