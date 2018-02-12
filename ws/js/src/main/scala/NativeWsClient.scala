package covenant.ws

import sloth._
import covenant.core.DefaultLogHandler
import mycelium.client._
import mycelium.core._
import mycelium.core.message._
import chameleon._
import cats.data.EitherT

import scala.concurrent.{Future, ExecutionContext}

private[ws] trait NativeWsClient {
  def apply[PickleType, Event, ErrorType](
    uri: String,
    config: WebsocketClientConfig,
    logger: LogHandler[Future]
  )(implicit
    ec: ExecutionContext,
    builder: JsMessageBuilder[PickleType],
    serializer: Serializer[ClientMessage[PickleType], PickleType],
    deserializer: Deserializer[ServerMessage[PickleType, Event, ErrorType], PickleType]
  ): WsClient[PickleType, Future, Event, ErrorType, ClientException] = {
    val connection = new JsWebsocketConnection
    WsClient.fromConnection(uri, connection, config, logger)
  }
  def apply[PickleType, Event, ErrorType](
    uri: String,
    config: WebsocketClientConfig
  )(implicit
    ec: ExecutionContext,
    builder: JsMessageBuilder[PickleType],
    serializer: Serializer[ClientMessage[PickleType], PickleType],
    deserializer: Deserializer[ServerMessage[PickleType, Event, ErrorType], PickleType]
  ): WsClient[PickleType, Future, Event, ErrorType, ClientException] = {
    apply[PickleType, Event, ErrorType](uri, config, new DefaultLogHandler[Future](identity))
  }

  def apply[PickleType, Event, ErrorType : ClientFailureConvert](
    uri: String,
    config: WebsocketClientConfig,
    recover: PartialFunction[Throwable, ErrorType] = PartialFunction.empty,
    logger: LogHandler[EitherT[Future, ErrorType, ?]] = null
  )(implicit
    ec: ExecutionContext,
    builder: JsMessageBuilder[PickleType],
    serializer: Serializer[ClientMessage[PickleType], PickleType],
    deserializer: Deserializer[ServerMessage[PickleType, Event, ErrorType], PickleType]
  ): WsClient[PickleType, EitherT[Future, ErrorType, ?], Event, ErrorType, ErrorType] = {
    val connection = new JsWebsocketConnection
    WsClient.fromConnection(uri, connection, config, recover, if (logger == null) new DefaultLogHandler[EitherT[Future, ErrorType, ?]](_.value) else logger)
  }
}
