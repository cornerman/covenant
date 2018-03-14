package covenant.ws.api

import sloth._

import scala.concurrent.Future

case class ScopedEvents[Event](privateEvents: List[Event], publicEvents: List[Event])

trait ApiConfiguration[Event, ErrorType, State] {
  def initialState: State
  def isStateValid(state: State): Boolean
  def applyEventsToState(state: State, events: Seq[Event]): State
  def serverFailure(error: ServerFailure): ErrorType
  def unhandledException(t: Throwable): ErrorType

  def eventDistributor: EventDistributor[Event, State]
  def scopeOutgoingEvents(events: List[Event]): ScopedEvents[Event]
  def adjustIncomingEvents(state: State, events: List[Event]): Future[List[Event]]

  final val dsl = new Dsl[Event, ErrorType, State](applyEventsToState, unhandledException)
}
trait ApiConfigurationWithDefaults[Event, ErrorType, State] extends ApiConfiguration[Event, ErrorType, State] {
  override def eventDistributor = new HashSetEventDistributor[Event, State]
  override def scopeOutgoingEvents(events: List[Event]) = ScopedEvents[Event](events, events)
  override def adjustIncomingEvents(state: State, events: List[Event]) = Future.successful(events)
}
