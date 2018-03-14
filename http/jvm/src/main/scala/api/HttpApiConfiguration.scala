package covenant.http.api

import sloth._
import covenant.core.api._
import akka.http.scaladsl.model._

import scala.concurrent.Future

trait HttpApiConfiguration[Event, ErrorType, State] {
  def requestToState(request: HttpRequest): Future[State]
  def publishEvents(events: List[Event]): Unit
}
