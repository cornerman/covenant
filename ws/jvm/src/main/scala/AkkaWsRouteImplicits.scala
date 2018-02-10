package covenant.ws

import mycelium.core._
import mycelium.core.message._
import mycelium.server._
import chameleon._

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._

trait AkkaHttpRouteImplicits {
  implicit class WsRouter[PickleType, Result[_]](val router: Router[PickleType, Result]) {
    def asWsRoute[Event, Failure, State](
      config: WebsocketServerConfig,
      handler: RequestHandler[PickleType, Event, Failure, State])(implicit
      system: ActorSystem,
      serializer: Serializer[ServerMessage[PickleType, Event, Failure], PickleType],
      deserializer: Deserializer[ClientMessage[PickleType], PickleType],
      builder: AkkaMessageBuilder[PickleType]): Route = {

      val websocketServer = WebsocketServer[PickleType, Event, Failure, State](config, handler)
      get {
        handleWebSocketMessages(websocketServer.flow())
      }
    }
  }
}
