package covenant.ws.api

import sloth._
import covenant.core.api._

import scala.concurrent.Future

case class ScopedEvents[Event](privateEvents: List[Event], publicEvents: List[Event])

trait WsApiConfiguration[Event, ErrorType, State] {
  def initialState: State
  def isStateValid(state: State): Boolean
  def serverFailure(error: ServerFailure): ErrorType
  def dsl: ApiDsl[Event, ErrorType, State]

  def eventDistributor: EventDistributor[Event]
  def scopeOutgoingEvents(events: List[Event]): ScopedEvents[Event]
  def adjustIncomingEvents(state: State, events: List[Event]): Future[List[Event]]
}
trait WsApiConfigurationWithDefaults[Event, ErrorType, State] extends WsApiConfiguration[Event, ErrorType, State] {
  override def eventDistributor = new HashSetEventDistributor[Event](_.notifyWithReaction)
  override def scopeOutgoingEvents(events: List[Event]) = ScopedEvents[Event](events, events)
  override def adjustIncomingEvents(state: State, events: List[Event]) = Future.successful(events)
}
