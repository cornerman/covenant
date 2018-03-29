package covenant.ws.api

import covenant.core.util.StopWatch
import covenant.core.api._
import sloth._
import mycelium.server._
import monix.execution.Scheduler
import cats.syntax.either._

import scala.concurrent.Future

//TODO rename to WsRequestHandler, move to covenant.ws?
class ApiRequestHandler[PickleType, Event, ErrorType, State](
  api: WsApiConfiguration[Event, ErrorType, State],
  router: Router[PickleType, ApiDsl[Event, ErrorType, State]#ApiFunction]
)(implicit
  scheduler: Scheduler
) extends FullRequestHandler[PickleType, Event, ErrorType, State] {
  import covenant.core.util.LogHelper._

  def initialState = Future.successful(api.initialState)

  override def onClientConnect(client: NotifiableClient[Event, State], state: Future[State]): Unit = {
    scribe.info(s"${clientDesc(client)} started")
    api.eventDistributor.subscribe(client)
  }

  override def onClientDisconnect(client: NotifiableClient[Event, State], state: Future[State], reason: DisconnectReason): Unit = {
    scribe.info(s"${clientDesc(client)} stopped: $reason")
    api.eventDistributor.unsubscribe(client)
  }

  override def onRequest(client: NotifiableClient[Event, State], originalState: Future[State], path: List[String], payload: PickleType): Response = {
    val watch = StopWatch.started

    val state = validateState(originalState)
    router(Request(path, payload)) match {

      case RouterResult.Success(arguments, apiFunction) =>
        val apiResponse = apiFunction.run(state)
        val newState = apiResponse.state

        val returnValue = apiResponse.value.map { value =>
          val rawResult = value.result.map(_.raw)
          val serializedResult = value.result.map(_.serialized)
          val events = filterAndDistributeEvents(client)(value.events)
          scribe.info(s"${clientDesc(client)} -->[response] ${requestLogLine(path, arguments, rawResult)} / $events. Took ${watch.readHuman}.")
          ReturnValue(serializedResult, events)
        }

        apiResponse.asyncEvents.foreach { rawEvents =>
          val events = filterAndDistributeEvents(client)(rawEvents)
          if (events.nonEmpty) {
            scribe.info(s"${clientDesc(client)} -->[async] ${requestLogLine(path, arguments, events)}. Took ${watch.readHuman}.")
            client.notify(_ => Future.successful(events))
          }
        }

        Response(newState, returnValue)

      case RouterResult.Failure(arguments, slothError) =>
        val error = api.serverFailure(slothError)
        scribe.warn(s"${clientDesc(client)} -->[failure] ${requestLogLine(path, arguments, error)}. Took ${watch.readHuman}.")
        Response(state, Future.successful(ReturnValue(Left(error), Nil)))

    }
  }

  override def onEvent(client: NotifiableClient[Event, State], originalState: Future[State], events: List[Event]): Reaction = {
    scribe.info(s"${clientDesc(client)} <--[events] $events")
    val state = validateState(originalState)
    val result = for {
      state <- state
      events <- api.adjustIncomingEvents(state, events)
    } yield (api.dsl.applyEventsToState(state, events), events)

    val newState = result.map(_._1)
    val newEvents = result.map(_._2)
    Reaction(newState, newEvents)
  }

  private def clientDesc(client: NotifiableClient[Event, State]): String = s"Client(${Integer.toString(client.hashCode, 36)})"

  private def validateState(state: Future[State]): Future[State] = state.flatMap { state =>
    if (api.isStateValid(state)) Future.successful(state)
    else Future.failed(new Exception("State is invalid"))
  }

  private def filterAndDistributeEvents[T](client: NotifiableClient[Event, State])(rawEvents: Seq[Event]): List[Event] = {
    val scoped = api.scopeOutgoingEvents(rawEvents.toList)
    api.eventDistributor.publish(scoped.publicEvents, origin = Some(client))
    scoped.privateEvents
  }
}
