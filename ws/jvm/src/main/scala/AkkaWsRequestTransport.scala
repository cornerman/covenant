package covenant.ws

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import chameleon._
import monix.execution.Scheduler
import mycelium.client._
import mycelium.core._
import mycelium.core.message._

object AkkaWsRequestTransport {
  def apply[PickleType, ErrorType](
    uri: String,
    config: WebsocketClientConfig = WebsocketClientConfig()
  )(implicit
    system: ActorSystem,
    materializer: ActorMaterializer,
    scheduler: Scheduler,
    builder: AkkaMessageBuilder[PickleType],
    serializer: Serializer[ClientMessage[PickleType], PickleType],
    deserializer: Deserializer[ServerMessage[PickleType, ErrorType], PickleType]
  ): WsRequestTransport[PickleType, ErrorType] = WsRequestTransport.fromConnection(uri, new AkkaWebsocketConnection, config)
}
