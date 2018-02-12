package covenant.ws.api

import sloth._
import chameleon._
import mycelium.server._
import mycelium.core._
import mycelium.core.message._
import akka.actor.ActorSystem
import monix.execution.Scheduler

import scala.concurrent.Future

case class ScopedEvents[Event](privateEvents: List[Event], publicEvents: List[Event])

trait ApiConfiguration[Event, ErrorType, State] {
  def initialState: State
  def isStateValid(state: State): Boolean
  def applyEventsToState(state: State, events: Seq[Event]): State
  def serverFailure(error: ServerFailure): ErrorType
  def unhandledException(t: Throwable): ErrorType

  def eventDistributor: EventDistributor[Event]
  def scopeOutgoingEvents(events: List[Event]): ScopedEvents[Event]
  def adjustIncomingEvents(state: State, events: List[Event]): Future[List[Event]]

  final val dsl = new Dsl[Event, ErrorType, State](applyEventsToState, unhandledException)
  final def asWsRoute[PickleType](
    router: Router[PickleType, dsl.ApiFunction],
    config: WebsocketServerConfig
  )(implicit
    scheduler: Scheduler,
    system: ActorSystem,
    serializer: Serializer[ServerMessage[PickleType, Event, ErrorType], PickleType],
    deserializer: Deserializer[ClientMessage[PickleType], PickleType],
    builder: AkkaMessageBuilder[PickleType]) = {

    val handler = new ApiRequestHandler[PickleType, Event, ErrorType, State](
      this, dsl, router.asInstanceOf[Router[PickleType, Dsl[Event, ErrorType, State]#ApiFunction]])
    router.asWsRoute(config, handler)
  }
}
trait ApiConfigurationWithDefaults[Event, ErrorType, State] extends ApiConfiguration[Event, ErrorType, State] {
  override def eventDistributor = new HashSetEventDistributor[Event]
  override def scopeOutgoingEvents(events: List[Event]) = ScopedEvents[Event](events, events)
  override def adjustIncomingEvents(state: State, events: List[Event]) = Future.successful(events)
}
