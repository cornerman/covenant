package covenant.ws.api

import covenant.{RequestResponse, RequestReturnValue}
import covenant.util.StopWatch
import covenant.ws.AkkaWsRoute.UnhandledServerFailure
import monix.eval.Task
import monix.execution.Scheduler
import mycelium.core.EventualResult
import mycelium.server._
import sloth._

import scala.concurrent.Future

class ApiRequestHandler[PickleType, Event, ErrorType, State](
  router: Router[PickleType, RequestResponse[State, ErrorType, ?]],
  initialStateValue: State,
  isStateValid: State => Boolean,
  recoverServerFailure: PartialFunction[ServerFailure, ErrorType],
  recoverThrowable: PartialFunction[Throwable, ErrorType]
)(implicit scheduler: Scheduler) extends StatefulRequestHandler[PickleType, ErrorType, State] {
  import covenant.util.LogHelper._

  override def initialState: Future[State] = Future.successful(initialStateValue)

  override def onClientConnect(client: ClientId, state: Future[State]): Unit = {
    scribe.info(s"$client started")
  }

  override def onClientDisconnect(client: ClientId, state: Future[State], reason: DisconnectReason): Unit = {
    scribe.info(s"$client stopped: $reason")
  }

  override def onRequest(client: ClientId, originalState: Future[State], path: List[String], payload: PickleType): Response = {
    val watch = StopWatch.started

    val state = validateState(originalState)
    val request = Request(path, payload)
    router(request) match {

      case RouterResult.Success(arguments, result) =>
        //TODO: share code with AkkaHttpRoute
        val response: Task[RequestResponse.Result[State, ErrorType, PickleType]] = result match {
          case result: RequestResponse.Result[State, ErrorType, PickleType] =>
            Task.pure(result)
          case stateFun: RequestResponse.StateFunction[State, ErrorType, PickleType] => Task.fromFuture(state).map { state =>
            stateFun.function(state)
          }
        }

        val resultTask = response.flatMap(_.value match {
          case RequestReturnValue.Single(task) => task.map {
            case Right(v) =>
              scribe.info(s"http -->[response] ${requestLogLine(path, arguments, v.raw)}. Took ${watch.readHuman}.")
              EventualResult.Single(v)
            case Left(e) =>
              scribe.warn(s"http -->[error] ${requestLogLine(path, arguments, e)}. Took ${watch.readHuman}.")
              EventualResult.Error(e)
          }
          case RequestReturnValue.Stream(task) => task.map {
            case Right(observable) =>
              val events = observable.map { v =>
                scribe.info(s"http -->[stream] ${requestLogLine(path, arguments, v.raw)}. Took ${watch.readHuman}.")
                v
              }.doOnComplete { () =>
                scribe.info(s"http -->[stream:complete] ${requestLogLine(path, arguments)}. Took ${watch.readHuman}.")
              }.doOnError { t =>
                scribe.warn(s"http -->[stream:error] ${requestLogLine(path, arguments)}. Took ${watch.readHuman}.", t)
              }

              EventualResult.Stream(events)
            case Left(e) =>
              scribe.warn(s"http -->[error] ${requestLogLine(path, arguments, e)}. Took ${watch.readHuman}.")
              EventualResult.Error(e)
          }
        })

        Response(state, resultTask)
      case RouterResult.Failure(arguments, e) =>
        val error = recoverServerFailure.lift(e)
          .fold[Task[EventualResult[PickleType, ErrorType]]](Task.raiseError(UnhandledServerFailure(e)))(e => Task.pure(EventualResult.Error(e)))

        scribe.warn(s"http -->[failure] ${requestLogLine(path, arguments, e)}. Took ${watch.readHuman}.")
        Response(state, error)
    }
  }

  private def validateState(state: Future[State]): Future[State] = state.flatMap { state =>
    if (isStateValid(state)) Future.successful(state)
    else Future.failed(new Exception("State is invalid"))
  }
}
