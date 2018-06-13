package covenant.ws

import sloth._
import mycelium.client._
import mycelium.core.message._
import chameleon._
import monix.reactive.subjects.PublishSubject
import monix.reactive.Observable
import cats.data.EitherT
import cats.implicits._
import monix.execution.Scheduler

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

abstract class WsClient[PickleType, Result[_], Failure, ErrorType](
  uri: String,
  connection: WebsocketConnection[PickleType],
  config: WebsocketClientConfig)
(implicit
  serializer: Serializer[ClientMessage[PickleType], PickleType],
  deserializer: Deserializer[ServerMessage[PickleType, Failure], PickleType]
){
  protected val mycelium = WebsocketClient[PickleType, Failure](connection, config)
  mycelium.run(uri)

  def observable = mycelium.observable

  def sendWithDefault = sendWith()
  def sendWith(sendType: SendType = SendType.WhenConnected, requestTimeout: Option[FiniteDuration] = Some(30 seconds)): Client[PickleType, Result, ErrorType]
}
object WsClient extends NativeWsClient {

  def fromConnection[PickleType, ErrorType](
    uri: String,
    connection: WebsocketConnection[PickleType],
    config: WebsocketClientConfig,
    logger: LogHandler
  )(implicit
    scheduler: Scheduler,
    serializer: Serializer[ClientMessage[PickleType], PickleType],
    deserializer: Deserializer[ServerMessage[PickleType, ErrorType], PickleType]
  ) = new WsClient[PickleType, Future, ErrorType, ClientException](uri, connection, config) {

    def sendWith(sendType: SendType, requestTimeout: Option[FiniteDuration]) = {
      val transport = new RequestTransport[PickleType, Future] {
        def apply(request: Request[PickleType]): Future[PickleType] = {
          mycelium.send(request.path, request.payload, sendType, requestTimeout).lastL.runAsync.map {
            case Right(res) => res
            case Left(err) => throw new Exception(s"Websocket request failed: $err")
          }
        }
      }

      Client[PickleType, Future, ClientException](transport, logger)
    }
  }

  def fromStreamableConnection[PickleType, ErrorType](
    uri: String,
    connection: WebsocketConnection[PickleType],
    config: WebsocketClientConfig,
    logger: LogHandler
  )(implicit
    scheduler: Scheduler,
    serializer: Serializer[ClientMessage[PickleType], PickleType],
    deserializer: Deserializer[ServerMessage[PickleType, ErrorType], PickleType]
  ) = new WsClient[PickleType, Observable, ErrorType, ClientException](uri, connection, config) {

    def sendWith(sendType: SendType, requestTimeout: Option[FiniteDuration]) = {
      val transport = new RequestTransport[PickleType, Observable] {
        def apply(request: Request[PickleType]): Observable[PickleType] = Observable(request).flatMap { request =>
          mycelium.send(request.path, request.payload, sendType, requestTimeout).map {
            case Right(res) => res
            case Left(err) => throw new Exception(s"Websocket request failed: $err")
          }
        }
      }

      Client[PickleType, Observable, ClientException](transport, logger)
    }
  }

  def fromConnection[PickleType, ErrorType : ClientFailureConvert](
    uri: String,
    connection: WebsocketConnection[PickleType],
    config: WebsocketClientConfig,
    recover: PartialFunction[Throwable, ErrorType],
    logger: LogHandler
  )(implicit
    scheduler: Scheduler,
    serializer: Serializer[ClientMessage[PickleType], PickleType],
    deserializer: Deserializer[ServerMessage[PickleType, ErrorType], PickleType]
  ) = new WsClient[PickleType, EitherT[Future, ErrorType, ?], ErrorType, ErrorType](uri, connection, config) {

    def sendWith(sendType: SendType, requestTimeout: Option[FiniteDuration]) = {
      val transport = new RequestTransport[PickleType, EitherT[Future, ErrorType, ?]] {
        def apply(request: Request[PickleType]): EitherT[Future, ErrorType, PickleType] =
          EitherT(mycelium.send(request.path, request.payload, sendType, requestTimeout).lastL.runAsync.recover(recover andThen Left.apply))
      }

      Client[PickleType, EitherT[Future, ErrorType, ?], ErrorType](transport, logger)
    }
  }
}

