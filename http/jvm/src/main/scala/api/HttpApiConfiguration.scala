package covenant.http.api

import akka.http.scaladsl.model._
import covenant.api._
import monix.reactive.Observable

import scala.concurrent.Future

trait HttpApiConfiguration[Event, ErrorType, State] {
  def requestToState(request: HttpRequest): Future[State]
  def publishEvents(events: Observable[List[Event]]): Unit

  val dsl: ServerDsl[Event, State]
}
