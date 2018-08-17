package covenant.ws

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.OverflowStrategy
import chameleon._
import covenant.RequestResponse
import covenant.ws.api.ApiRequestHandler
import monix.execution.Scheduler
import mycelium.core._
import mycelium.core.message._
import mycelium.server._
import sloth._

object AkkaWsRoute {
  case class UnhandledServerFailure(failure: ServerFailure) extends Exception(s"Unhandled server failure: $failure")

  def defaultServerConfig = WebsocketServerConfig(bufferSize = 100, overflowStrategy = OverflowStrategy.fail, parallelism = Runtime.getRuntime.availableProcessors)

  def fromRouter[PickleType : AkkaMessageBuilder, ErrorType](
    router: Router[PickleType, RequestResponse[Unit, ErrorType, ?]],
    config: WebsocketServerConfig = defaultServerConfig,
    recoverServerFailure: PartialFunction[ServerFailure, ErrorType] = PartialFunction.empty,
    recoverThrowable: PartialFunction[Throwable, ErrorType] = PartialFunction.empty)(implicit
    system: ActorSystem,
    scheduler: Scheduler,
    serializer: Serializer[ServerMessage[PickleType, ErrorType], PickleType],
    deserializer: Deserializer[ClientMessage[PickleType], PickleType]): Route = fromRouterWithState[PickleType, ErrorType, Unit](router, (), _ => true, config, recoverServerFailure, recoverThrowable)

  def fromRouterWithState[PickleType : AkkaMessageBuilder, ErrorType, State](
    router: Router[PickleType, RequestResponse[State, ErrorType, ?]],
    initialState: State,
    isStateValid: State => Boolean = (_: State) => true,
    config: WebsocketServerConfig = defaultServerConfig,
    recoverServerFailure: PartialFunction[ServerFailure, ErrorType] = PartialFunction.empty,
    recoverThrowable: PartialFunction[Throwable, ErrorType] = PartialFunction.empty)(implicit
    system: ActorSystem,
    scheduler: Scheduler,
    serializer: Serializer[ServerMessage[PickleType, ErrorType], PickleType],
    deserializer: Deserializer[ClientMessage[PickleType], PickleType]): Route = {

    val handler = new ApiRequestHandler(router, initialState, isStateValid, recoverServerFailure, recoverThrowable)
    val websocketServer = WebsocketServer[PickleType, ErrorType, State](config, handler)

    get {
      handleWebSocketMessages(websocketServer.flow())
    }
  }
}
