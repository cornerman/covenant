package covenant.ws

import sloth._
import covenant.ws.api._
import covenant.core.api._
import mycelium.core._
import mycelium.core.message._
import mycelium.server._
import chameleon._
import cats.data.EitherT

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import monix.execution.Scheduler

import scala.concurrent.Future

object AkkaWsRoute {
  def fromRouter[PickleType : AkkaMessageBuilder, Result[_], Event, ErrorType, State](router: Router[PickleType, Result], config: WebsocketServerConfig, handler: RequestHandler[PickleType, Event, ErrorType, State])(implicit
    system: ActorSystem,
    serializer: Serializer[ServerMessage[PickleType, Event, ErrorType], PickleType],
    deserializer: Deserializer[ClientMessage[PickleType], PickleType]): Route = {

    val websocketServer = WebsocketServer[PickleType, Event, ErrorType, State](config, handler)
    get {
      handleWebSocketMessages(websocketServer.flow())
    }
  }

  def fromApiRouter[PickleType : AkkaMessageBuilder, Event, ErrorType, State](
    router: Router[PickleType, ApiDsl[Event, ErrorType, State]#ApiFunction],
    config: WebsocketServerConfig,
    api: WsApiConfiguration[Event, ErrorType, State]
  )(implicit
    scheduler: Scheduler,
    system: ActorSystem,
    serializer: Serializer[ServerMessage[PickleType, Event, ErrorType], PickleType],
    deserializer: Deserializer[ClientMessage[PickleType], PickleType]) = {

    val handler = new ApiRequestHandler[PickleType, Event, ErrorType, State](api, router)
    fromRouter(router, config, handler)
  }

  def fromFutureRouter[PickleType : AkkaMessageBuilder, ErrorType](
    router: Router[PickleType, Future],
    config: WebsocketServerConfig,
    failedRequestError: ServerFailure => ErrorType)(implicit
    system: ActorSystem,
    serializer: Serializer[ServerMessage[PickleType, Unit, ErrorType], PickleType],
    deserializer: Deserializer[ClientMessage[PickleType], PickleType]): Route = {
    import system.dispatcher

    val handler = new SimpleStatelessRequestHandler[PickleType, Unit, ErrorType] {
      override def onClientConnect(): Unit = {
        scribe.info("Client connected")
      }
      override def onClientDisconnect(reason: DisconnectReason): Unit = {
        scribe.info(s"Client disconnected: $reason")
      }
      override def onRequest(path: List[String], payload: PickleType): Response = {
        router(Request(path, payload)).toEither match {
          case Right(res) =>
            val recoveredResult = res.map(Right(_)).recover { case t => Left(failedRequestError(ServerFailure.HandlerError(t))) }
            Response(recoveredResult.map(ReturnValue(_)))
          case Left(err) =>
            Response(Future.successful(ReturnValue(Left(failedRequestError(err)))))
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
    serializer: Serializer[ServerMessage[PickleType, Unit, ErrorType], PickleType],
    deserializer: Deserializer[ClientMessage[PickleType], PickleType]): Route = {
    import system.dispatcher

    val handler = new SimpleStatelessRequestHandler[PickleType, Unit, ErrorType] {
      override def onClientConnect(): Unit = {
        scribe.info("Client connected")
      }
      override def onClientDisconnect(reason: DisconnectReason): Unit = {
        scribe.info(s"Client disconnected: $reason")
      }
      override def onRequest(path: List[String], payload: PickleType): Response = {
        router(Request(path, payload)).toEither match {
          case Right(res) =>
            val recoveredResult = res.value.recover { case t => Left(failedRequestError(ServerFailure.HandlerError(t))) }
            Response(recoveredResult.map(ReturnValue(_)))
          case Left(err) => Response(Future.successful(ReturnValue(Left(failedRequestError(err)))))
        }
      }
    }

    fromRouter[PickleType, EitherT[Future, ErrorType, ?], Unit, ErrorType, Unit](router, config, handler)
  }
}
