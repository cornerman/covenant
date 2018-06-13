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

private[ws] trait NativeWsClient {
  def apply[PickleType, ErrorType](
    uri: String,
    config: WebsocketClientConfig,
    logger: LogHandler
  )(implicit
    scheduler: Scheduler,
    builder: JsMessageBuilder[PickleType],
    serializer: Serializer[ClientMessage[PickleType], PickleType],
    deserializer: Deserializer[ServerMessage[PickleType, ErrorType], PickleType]
  ): WsClient[PickleType, Future, ErrorType, ClientException] = {
    val connection = new JsWebsocketConnection
    WsClient.fromConnection(uri, connection, config, logger)
  }
  def apply[PickleType, ErrorType](
    uri: String,
    config: WebsocketClientConfig
  )(implicit
    scheduler: Scheduler,
    builder: JsMessageBuilder[PickleType],
    serializer: Serializer[ClientMessage[PickleType], PickleType],
    deserializer: Deserializer[ServerMessage[PickleType, ErrorType], PickleType]
  ): WsClient[PickleType, Future, ErrorType, ClientException] = {
    apply[PickleType, ErrorType](uri, config, DefaultLogHandler)
  }

  def streamable[PickleType, ErrorType](
    uri: String,
    config: WebsocketClientConfig,
    logger: LogHandler
  )(implicit
    scheduler: Scheduler,
    builder: JsMessageBuilder[PickleType],
    serializer: Serializer[ClientMessage[PickleType], PickleType],
    deserializer: Deserializer[ServerMessage[PickleType, ErrorType], PickleType]
  ): WsClient[PickleType, Observable, ErrorType, ClientException] = {
    val connection = new JsWebsocketConnection
    WsClient.fromStreamableConnection(uri, connection, config, logger)
  }
  def streamable[PickleType, ErrorType](
    uri: String,
    config: WebsocketClientConfig
  )(implicit
    scheduler: Scheduler,
    builder: JsMessageBuilder[PickleType],
    serializer: Serializer[ClientMessage[PickleType], PickleType],
    deserializer: Deserializer[ServerMessage[PickleType, ErrorType], PickleType]
  ): WsClient[PickleType, Observable, ErrorType, ClientException] = {
    streamable[PickleType, ErrorType](uri, config, DefaultLogHandler)
  }

  def apply[PickleType, ErrorType : ClientFailureConvert](
    uri: String,
    config: WebsocketClientConfig,
    recover: PartialFunction[Throwable, ErrorType] = PartialFunction.empty,
    logger: LogHandler = DefaultLogHandler
  )(implicit
    scheduler: Scheduler,
    builder: JsMessageBuilder[PickleType],
    serializer: Serializer[ClientMessage[PickleType], PickleType],
    deserializer: Deserializer[ServerMessage[PickleType, ErrorType], PickleType]
  ): WsClient[PickleType, EitherT[Future, ErrorType, ?], ErrorType, ErrorType] = {
    val connection = new JsWebsocketConnection
    WsClient.fromConnection(uri, connection, config, recover, logger)
  }
}
