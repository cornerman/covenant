package covenant.ws.api

import sloth._

trait WsApiConfiguration[Event, ErrorType, State] {
  def initialState: State
  def isStateValid(state: State): Boolean
  def serverFailure(error: ServerFailure): ErrorType
  def unhandledException: PartialFunction[Throwable, ErrorType]
  def scopeOutgoingEvents(events: List[Event]): ScopedEvents[Event]
  def eventDistributor: EventDistributor[Event]
}
trait WsApiConfigurationWithDefaults[Event, ErrorType, State] extends WsApiConfiguration[Event, ErrorType, State] {
  override def scopeOutgoingEvents(events: List[Event]) = ScopedEvents[Event](events, events)
  override def unhandledException: PartialFunction[Throwable, ErrorType] = PartialFunction.empty
  override def eventDistributor = new HashMapEventDistributor[Event]
}

case class ScopedEvents[Event](privateEvents: List[Event], publicEvents: List[Event])
