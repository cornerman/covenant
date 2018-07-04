package covenant.ws

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.OverflowStrategy
import chameleon._
import covenant.RequestResponse
import covenant.api._
import covenant.ws.api._
import monix.eval.Task
import monix.execution.Scheduler
import mycelium.core._
import mycelium.core.message._
import mycelium.server._
import sloth._

object AkkaWsRoute {
  case class UnhandledServerFailure(failure: ServerFailure) extends Exception(s"Unhandled server failure: $failure")

  def defaultServerConfig = WebsocketServerConfig(bufferSize = 100, overflowStrategy = OverflowStrategy.fail, parallelism = Runtime.getRuntime.availableProcessors)

//  def fromApiRouter[PickleType : AkkaMessageBuilder, ErrorType, Event, State](
//    router: Router[PickleType, RawServerDsl.ApiFunction[Event, State, ?]],
//    api: WsApiConfiguration[Event, ErrorType, State],
//    config: WebsocketServerConfig = defaultServerConfig
//  )(implicit
//    system: ActorSystem,
//    scheduler: Scheduler,
//    serializer: Serializer[ServerMessage[PickleType, ErrorType], PickleType],
//    deserializer: Deserializer[ClientMessage[PickleType], PickleType]) = {
//
//    val handler = new ApiRequestHandler[PickleType, Event, ErrorType, State](api, router)
//    routerToRoute(router, handler, config)
//  }

  def fromRouter[PickleType : AkkaMessageBuilder, ErrorType, State](
    router: Router[PickleType, RequestResponse[State, ErrorType, ?]],
    config: WebsocketServerConfig = defaultServerConfig,
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
        router(Request(path, payload)).toEither match {
          case Right(res) => res match {
            //TODO: dupe
            case RequestResponse.Single(task) => Response {
              task.map {
                case Right(v) => EventualResult.Single(v)
                case Left(err) => EventualResult.Error(err)
              }.onErrorRecover(recoverThrowable andThen EventualResult.Error.apply)
            }
            case RequestResponse.Stream(task) => Response {
              task.map {
                case Right(v) => EventualResult.Stream(v)
                case Left(err) => EventualResult.Error(err)
              }.onErrorRecover(recoverThrowable andThen EventualResult.Error.apply)
            }
          }
          case Left(failure) => recoverServerFailure.lift(failure) match {
            case Some(err) => Response(Task.pure(EventualResult.Error(err)))
            case None => Response(Task.raiseError(UnhandledServerFailure(failure)))
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
