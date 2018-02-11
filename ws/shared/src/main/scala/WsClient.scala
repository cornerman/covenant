package covenant.ws

import sloth._
import mycelium.client._

import cats.data.EitherT
import cats.implicits._

import scala.concurrent.duration._
import scala.concurrent.{Future, ExecutionContext}

object WsClient {
  def apply[PickleType, ErrorType : ClientFailureConvert](
    client: WebsocketClient[PickleType, _, ErrorType],
    sendType: SendType = SendType.WhenConnected,
    requestTimeout: FiniteDuration = 30 seconds,
    logger: LogHandler[Future] = new LogHandler[Future],
    recover: PartialFunction[Throwable, ErrorType] = PartialFunction.empty
  )(implicit ec: ExecutionContext): Client[PickleType, EitherT[Future, ErrorType, ?], ErrorType] = {

    val transport = new RequestTransport[PickleType, EitherT[Future, ErrorType, ?]] {
      def apply(request: Request[PickleType]): EitherT[Future, ErrorType, PickleType] =
        EitherT(client.send(request.path, request.payload, sendType, requestTimeout).recover(recover andThen Left.apply))
    }

    Client[PickleType, EitherT[Future, ErrorType, ?], ErrorType](transport)
  }

  def apply[PickleType](
    client: WebsocketClient[PickleType, _, _],
    sendType: SendType,
    requestTimeout: FiniteDuration,
    logger: LogHandler[Future]
  )(implicit ec: ExecutionContext): Client[PickleType, Future, ClientException] = {

    val transport = new RequestTransport[PickleType, Future] {
      def apply(request: Request[PickleType]): Future[PickleType] = {
        client.send(request.path, request.payload, sendType, requestTimeout).flatMap {
          case Right(res) => Future.successful(res)
          case Left(err) => Future.failed(new Exception(s"Websocket request failed: $err"))
        }
      }
    }

    Client[PickleType, Future, ClientException](transport)
  }

  def apply[PickleType](client: WebsocketClient[PickleType, _, _])(implicit ec: ExecutionContext): Client[PickleType, Future, ClientException] = apply(client, SendType.WhenConnected, 30 seconds)
  def apply[PickleType](client: WebsocketClient[PickleType, _, _], sendType: SendType)(implicit ec: ExecutionContext): Client[PickleType, Future, ClientException] = apply(client, sendType, 30 seconds)
  def apply[PickleType](client: WebsocketClient[PickleType, _, _], requestTimeout: FiniteDuration)(implicit ec: ExecutionContext): Client[PickleType, Future, ClientException] = apply(client, SendType.WhenConnected, requestTimeout)
  def apply[PickleType](client: WebsocketClient[PickleType, _, _], sendType: SendType, requestTimeout: FiniteDuration)(implicit ec: ExecutionContext): Client[PickleType, Future, ClientException] = apply(client, sendType, requestTimeout, new LogHandler[Future])
  def apply[PickleType](client: WebsocketClient[PickleType, _, _], sendType: SendType, logger: LogHandler[Future])(implicit ec: ExecutionContext): Client[PickleType, Future, ClientException] = apply(client, sendType, 30.seconds, logger)
  def apply[PickleType](client: WebsocketClient[PickleType, _, _], requestTimeout: FiniteDuration, logger: LogHandler[Future])(implicit ec: ExecutionContext): Client[PickleType, Future, ClientException] = apply(client, SendType.WhenConnected, requestTimeout, logger)
}
