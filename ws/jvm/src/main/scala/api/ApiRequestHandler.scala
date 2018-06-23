package covenant.ws.api

import covenant.api._
import covenant.util.StopWatch
import monix.eval.Task
import monix.execution.Scheduler
import mycelium.server._
import sloth._

import scala.concurrent.Future

class ApiRequestHandler[PickleType, Event, ErrorType, State](
  api: WsApiConfiguration[Event, ErrorType, State],
  router: Router[PickleType, RawServerDsl.ApiFunction[Event, State, ?]]
)(implicit scheduler: Scheduler) extends StatefulRequestHandler[PickleType, ErrorType, State] {
  import covenant.util.LogHelper._

  def initialState = Future.successful(api.initialState)

  override def onClientConnect(client: ClientId, state: Future[State]): Unit = {
    scribe.info(s"$client started")
    api.eventDistributor.subscribe(client)
  }

  override def onClientDisconnect(client: ClientId, state: Future[State], reason: DisconnectReason): Unit = {
    scribe.info(s"$client stopped: $reason")
    api.eventDistributor.unsubscribe(client)
  }

  override def onRequest(client: ClientId, originalState: Future[State], path: List[String], payload: PickleType): Response = {
    val watch = StopWatch.started

    val state = validateState(originalState)
    val request = Request(path, payload)
    router(request) match {

      case RouterResult.Success(arguments, apiFunction) => apiFunction match {
        case f: RawServerDsl.ApiFunction.Single[Event, State, RouterResult.Value[PickleType]] =>
          val result = f.run(state)
          val newState = result.state

          ???

        case f: RawServerDsl.ApiFunction.Stream[Event, State, RouterResult.Value[PickleType]] =>
          val result = f.run(state)
          val newState = result.state

          ???
      }

//        val returnValue = result.action.value match {
//          case ApiValue.Single(future) => future.map { value =>
//            val rawResult = value.result.map(_.raw)
//            val serializedResult = value.result.map(_.serialized)
//            val events = filterAndDistributeEvents(client)(value.events)
//            scribe.info(s"$client -->[response] ${requestLogLine(path, arguments, rawResult)} / $events. Took ${watch.readHuman}.")
//            client.notify(events)
//            serializedResult
//          }
//          case ApiValue.Stream(observable) =>
//        }
//
//        result.action.events.foreach { rawEvents =>
//          val events = filterAndDistributeEvents(client)(rawEvents)
//          if (events.nonEmpty) {
//            scribe.info(s"$client -->[async] ${requestLogLine(path, arguments, events)}. Took ${watch.readHuman}.")
//            client.notify(events)
//          }
//        }
//
//        Response(newState, returnValue)

      case RouterResult.Failure(arguments, slothError) =>
        val error = api.serverFailure(slothError)
        scribe.warn(s"$client -->[failure] ${requestLogLine(path, arguments, error)}. Took ${watch.readHuman}.")
        Response(state, Task(Left(error)))

    }
  }

  private def validateState(state: Future[State]): Future[State] = state.flatMap { state =>
    if (api.isStateValid(state)) Future.successful(state)
    else Future.failed(new Exception("State is invalid"))
  }

  private def filterAndDistributeEvents[T](client: ClientId)(rawEvents: Seq[Event]): List[Event] = {
    val scoped = api.scopeOutgoingEvents(rawEvents.toList)
    api.eventDistributor.publish(scoped.publicEvents, origin = Some(client))
    scoped.privateEvents
  }
}
