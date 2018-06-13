package covenant.ws

import sloth._
import covenant.ws.api._
import covenant.core.api._
import mycelium.core._
import mycelium.core.message._
import mycelium.server._
import chameleon._
import cats.implicits._
import cats.data.EitherT
import akka.actor.ActorSystem
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import monix.execution.Scheduler
import monix.reactive.Observable

import scala.concurrent.Future

object AkkaWsRoute {
  def fromRouter[PickleType : AkkaMessageBuilder, Result[_], Event, ErrorType, State](router: Router[PickleType, Result], config: WebsocketServerConfig, handler: RequestHandler[PickleType, ErrorType, State])(implicit
    system: ActorSystem,
    scheduler: Scheduler,
    serializer: Serializer[ServerMessage[PickleType, ErrorType], PickleType],
    deserializer: Deserializer[ClientMessage[PickleType], PickleType]): Route = {

    val websocketServer = WebsocketServer[PickleType, ErrorType, State](config, handler)
    get {
      handleWebSocketMessages(websocketServer.flow())
    }
  }

  def fromApiRouter[PickleType : AkkaMessageBuilder, Event, ErrorType, State](
    router: Router[PickleType, RawServerDsl.ApiFunctionT[Event, State, ?]],
    config: WebsocketServerConfig,
    api: WsApiConfiguration[Event, ErrorType, State]
  )(implicit
    system: ActorSystem,
    scheduler: Scheduler,
    serializer: Serializer[ServerMessage[PickleType, ErrorType], PickleType],
    deserializer: Deserializer[ClientMessage[PickleType], PickleType]) = {

    val handler = new ApiRequestHandler[PickleType, Event, ErrorType, State](api, router)
    fromRouter(router, config, handler)
  }

  def fromFutureRouter[PickleType : AkkaMessageBuilder, ErrorType](
    router: Router[PickleType, Future],
    config: WebsocketServerConfig,
    failedRequestError: ServerFailure => ErrorType)(implicit
    system: ActorSystem,
    scheduler: Scheduler,
    serializer: Serializer[ServerMessage[PickleType, ErrorType], PickleType],
    deserializer: Deserializer[ClientMessage[PickleType], PickleType]): Route = {

    val handler = new StatelessRequestHandler[PickleType, ErrorType] {
      override def onClientConnect(client: ClientId): Unit = {
        scribe.info(s"Client connected ($client)")
      }
      override def onClientDisconnect(client: ClientId, reason: DisconnectReason): Unit = {
        scribe.info(s"Client disconnected ($client): $reason")
      }
      override def onRequest(client: ClientId, path: List[String], payload: PickleType): Response = {
        router(Request(path, payload)).toEither match {
          case Right(res) =>
            val recoveredResult = res.map(Right.apply).recover { case t => Left(failedRequestError(ServerFailure.HandlerError(t))) }
            Response(recoveredResult)
          case Left(err) =>
            Response(Future.successful(Left(failedRequestError(err))))
        }
      }
    }

    fromRouter[PickleType, Future, Unit, ErrorType, Unit](router, config, handler)
  }

  def fromFutureEitherRouter[PickleType : AkkaMessageBuilder, ErrorType](
    router: Router[PickleType, EitherT[Future, ErrorType, ?]],
    config: WebsocketServerConfig,
    failedRequestError: ServerFailure => ErrorType)(implicit
    system: ActorSystem,
    scheduler: Scheduler,
    serializer: Serializer[ServerMessage[PickleType, ErrorType], PickleType],
    deserializer: Deserializer[ClientMessage[PickleType], PickleType]): Route = {

    val handler = new StatelessRequestHandler[PickleType, ErrorType] {
      override def onClientConnect(client: ClientId): Unit = {
        scribe.info(s"Client connected ($client)")
      }
      override def onClientDisconnect(client: ClientId, reason: DisconnectReason): Unit = {
        scribe.info(s"Client disconnected ($client): $reason")
      }
      override def onRequest(client: ClientId, path: List[String], payload: PickleType): Response = {
        router(Request(path, payload)).toEither match {
          case Right(res) =>
            val recoveredResult = res.value.recover { case t => Left(failedRequestError(ServerFailure.HandlerError(t))) }
            Response(recoveredResult)
          case Left(err) =>
            Response(Future.successful(Left(failedRequestError(err))))
        }
      }
    }

    fromRouter[PickleType, EitherT[Future, ErrorType, ?], Unit, ErrorType, Unit](router, config, handler)
  }

  def fromObservableRouter[PickleType : AkkaMessageBuilder, ErrorType](
    router: Router[PickleType, Observable],
    config: WebsocketServerConfig,
    failedRequestError: ServerFailure => ErrorType)(implicit
    system: ActorSystem,
    scheduler: Scheduler,
    serializer: Serializer[ServerMessage[PickleType, ErrorType], PickleType],
    deserializer: Deserializer[ClientMessage[PickleType], PickleType]): Route = {

    val handler = new StatelessRequestHandler[PickleType, ErrorType] {
      override def onClientConnect(client: ClientId): Unit = {
        scribe.info(s"Client connected ($client)")
      }
      override def onClientDisconnect(client: ClientId, reason: DisconnectReason): Unit = {
        scribe.info(s"Client disconnected ($client): $reason")
      }
      override def onRequest(client: ClientId, path: List[String], payload: PickleType): Response = {
        router(Request(path, payload)).toEither match {
          case Right(res) =>
            val recoveredResult = res.map(Right(_)).onErrorHandle(t => Left(failedRequestError(ServerFailure.HandlerError(t))))
            Response(recoveredResult)
          case Left(err) =>
            Response(Future.successful(Left(failedRequestError(err))))
        }
      }
    }

    fromRouter[PickleType, Observable, Unit, ErrorType, Unit](router, config, handler)
  }
}
