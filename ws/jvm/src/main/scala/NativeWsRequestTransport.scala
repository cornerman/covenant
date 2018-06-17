package covenant.ws

import sloth._
import covenant.core.DefaultLogHandler
import mycelium.client._
import mycelium.client._
import mycelium.core._
import mycelium.core.message._
import chameleon._
import cats.data.EitherT
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.actor.ActorSystem
import monix.execution.Scheduler
import cats.implicits._
import monix.reactive.Observable

import scala.concurrent.Future

private[ws] trait NativeWsRequestTransport {
  def apply[PickleType, ErrorType](
    uri: String,
    config: WebsocketClientConfig = WebsocketClientConfig(),
    bufferSize: Int = 100,
    overflowStrategy: OverflowStrategy = OverflowStrategy.fail
  )(implicit
    system: ActorSystem,
    materializer: ActorMaterializer,
    scheduler: Scheduler,
    builder: AkkaMessageBuilder[PickleType],
    serializer: Serializer[ClientMessage[PickleType], PickleType],
    deserializer: Deserializer[ServerMessage[PickleType, ErrorType], PickleType]
  ) = {
    val connection = new AkkaWebsocketConnection(bufferSize, overflowStrategy)
    WsRequestTransport.fromConnection(uri, connection, config)
  }
}
