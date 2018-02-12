package covenant.ws

import sloth._
import mycelium.client._
import mycelium.core._
import mycelium.core.message._
import chameleon._
import cats.data.EitherT

import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.actor.ActorSystem

import scala.concurrent.Future

private[ws] trait NativeWsClient {
  //TODO: configure?
  private val defaultBufferSize = 100
  private val defaultOverflowStrategy = OverflowStrategy.fail

  def apply[PickleType, Event, ErrorType](
    uri: String,
    config: WebsocketClientConfig,
    logger: LogHandler[Future]
  )(implicit
    system: ActorSystem,
    materializer: ActorMaterializer,
    builder: AkkaMessageBuilder[PickleType],
    serializer: Serializer[ClientMessage[PickleType], PickleType],
    deserializer: Deserializer[ServerMessage[PickleType, Event, ErrorType], PickleType]
  ): WsClient[PickleType, Future, Event, ErrorType, ClientException] = {
    import system.dispatcher
    val connection = new AkkaWebsocketConnection(defaultBufferSize, defaultOverflowStrategy)
    WsClient.fromConnection(uri, connection, config, logger)
  }
  def apply[PickleType, Event, ErrorType](
    uri: String,
    config: WebsocketClientConfig
  )(implicit
    system: ActorSystem,
    materializer: ActorMaterializer,
    builder: AkkaMessageBuilder[PickleType],
    serializer: Serializer[ClientMessage[PickleType], PickleType],
    deserializer: Deserializer[ServerMessage[PickleType, Event, ErrorType], PickleType]
  ): WsClient[PickleType, Future, Event, ErrorType, ClientException] = apply[PickleType, Event, ErrorType](uri, config, new LogHandler[Future])

  def apply[PickleType, Event, ErrorType : ClientFailureConvert](
    uri: String,
    config: WebsocketClientConfig,
    recover: PartialFunction[Throwable, ErrorType] = PartialFunction.empty,
    logger: LogHandler[EitherT[Future, ErrorType, ?]] = new LogHandler[EitherT[Future, ErrorType, ?]]
  )(implicit
    system: ActorSystem,
    materializer: ActorMaterializer,
    builder: AkkaMessageBuilder[PickleType],
    serializer: Serializer[ClientMessage[PickleType], PickleType],
    deserializer: Deserializer[ServerMessage[PickleType, Event, ErrorType], PickleType]
  ): WsClient[PickleType, EitherT[Future, ErrorType, ?], Event, ErrorType, ErrorType] = {
    import system.dispatcher
    val connection = new AkkaWebsocketConnection(defaultBufferSize, defaultOverflowStrategy)
    WsClient.fromConnection(uri, connection, config, recover, logger)
  }
}
