package covenant.ws

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.OverflowStrategy
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
  case class UnhandledServerFailure(failure: ServerFailure) extends Exception(s"Unhandled server failure: $failure")

  def fromApiRouter[PickleType : AkkaMessageBuilder, ErrorType, Event, State](
    router: Router[PickleType, RawServerDsl.ApiFunctionT[Event, State, ?]],
    api: WsApiConfiguration[Event, ErrorType, State],
    config: WebsocketServerConfig = WebsocketServerConfig(bufferSize = 100, overflowStrategy = OverflowStrategy.fail)
  )(implicit
    system: ActorSystem,
    scheduler: Scheduler,
    serializer: Serializer[ServerMessage[PickleType, ErrorType], PickleType],
    deserializer: Deserializer[ClientMessage[PickleType], PickleType]) = {

    val handler = new ApiRequestHandler[PickleType, Event, ErrorType, State](api, router)
    routerToRoute(router, handler, config)
  }

  def fromRouter[PickleType : AkkaMessageBuilder, ErrorType](
    router: Router[PickleType, RequestResponse],
    config: WebsocketServerConfig = WebsocketServerConfig(bufferSize = 100, overflowStrategy = OverflowStrategy.fail),
    recoverServerFailure: PartialFunction[ServerFailure, ErrorType] = PartialFunction.empty,
    recoverThrowable: PartialFunction[Throwable, ErrorType] = PartialFunction.empty)(implicit
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
        val failureThrowable: PartialFunction[Throwable, ServerFailure] = { case t => ServerFailure.HandlerError(t) }
        router(Request(path, payload)).toEither match {
          case Right(res) => res match {
            case RequestResponse.Single(task) =>
              val recoveredResult = task.runAsync.map(Right.apply).recover(recoverThrowable andThen Left.apply)
              Response(recoveredResult)
            case RequestResponse.Stream(observable) =>
              val recoveredResult = observable.map(Right.apply).onErrorRecover(recoverThrowable andThen Left.apply)
              Response(recoveredResult)
          }
          case Left(failure) => recoverServerFailure.lift(failure) match {
            case Some(err) => Response(Future.successful(Left(err)))
            case None => Response(Future.failed(UnhandledServerFailure(failure)))
          }

        }
      }
    }

    routerToRoute(router, handler, config)
  }

  private def routerToRoute[PickleType : AkkaMessageBuilder, Result[_], ErrorType, Event, State](
    router: Router[PickleType, Result],
    handler: RequestHandler[PickleType, ErrorType, State],
    config: WebsocketServerConfig
  )(implicit
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
