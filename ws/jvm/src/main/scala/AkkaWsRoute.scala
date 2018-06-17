package covenant.ws

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.data.EitherT
import cats.implicits._
import chameleon._
import covenant.RequestResponse
import covenant.api._
import covenant.ws.api._
import monix.execution.Scheduler
import monix.reactive.Observable
import mycelium.core._
import mycelium.core.message._
import mycelium.server._
import sloth._

import scala.concurrent.Future

object AkkaWsRoute {
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
    routerToRoute(router, config, handler)
  }

  def fromRouter[PickleType : AkkaMessageBuilder, ErrorType](
    router: Router[PickleType, RequestResponse],
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
          case Right(res) => res match {
            case RequestResponse.Single(task) =>
              val recoveredResult = task.runAsync.map(Right.apply).recover { case t => Left(failedRequestError(ServerFailure.HandlerError(t))) }
              Response(recoveredResult)
            case RequestResponse.Stream(observable) =>
              val recoveredResult = observable.map(Right.apply).onErrorHandle(t => Left(failedRequestError(ServerFailure.HandlerError(t))))
              Response(recoveredResult)
          }
          case Left(err) =>
            Response(Future.successful(Left(failedRequestError(err))))
        }
      }
    }

    routerToRoute[PickleType, RequestResponse, Unit, ErrorType, Unit](router, config, handler)
  }

  private def routerToRoute[PickleType : AkkaMessageBuilder, Result[_], Event, ErrorType, State](router: Router[PickleType, Result], config: WebsocketServerConfig, handler: RequestHandler[PickleType, ErrorType, State])(implicit
    system: ActorSystem,
    scheduler: Scheduler,
    serializer: Serializer[ServerMessage[PickleType, ErrorType], PickleType],
    deserializer: Deserializer[ClientMessage[PickleType], PickleType]): Route = {

    val websocketServer = WebsocketServer[PickleType, ErrorType, State](config, handler)
    get {
      handleWebSocketMessages(websocketServer.flow())
    }
  }
}
