package covenant.http

import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Route, RouteResult}
import akka.http.scaladsl.unmarshalling._
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Source
import covenant._
import covenant.api._
import covenant.http.api._
import covenant.util.StopWatch
import monix.eval.Task
import monix.execution.Scheduler
import sloth._

import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, _}
import scala.util.{Failure, Success, Try}

case class HttpServerConfig(keepAliveInterval: FiniteDuration = 30 seconds)

object AkkaHttpRoute {
   import covenant.util.LogHelper._

//   def fromApiRouter[PickleType : FromRequestUnmarshaller : ToResponseMarshaller, Event, ErrorType, State](
//     router: Router[PickleType, RawServerDsl.ApiFunction[Event, State, ?]],
//     api: HttpApiConfiguration[Event, ErrorType, State])(implicit
//     scheduler: Scheduler): Route = {
//
//     requestFunctionToRouteWithHeaders[PickleType] { (r, httpRequest) =>
//       val watch = StopWatch.started
//       val state: Future[State] = api.requestToState(httpRequest)
//       val path = httpRequest.getUri.toString.split("/").toList //TODO
//
//       router(r) match {
//         case RouterResult.Success(arguments, apiFunction) =>
//           val apiResponse = apiFunction.run(state)
//
//           val returnValue = {
//             apiResponse.value match {
//               case RequestResponse.Single(task) =>
////                 val rawResult = future.map(_.raw)
////                 val serializedResult = future.map(_.serialized)
////                 scribe.info(s"http -->[response] ${requestLogLine(path, arguments, rawResult)} / ${events}. Took ${watch.readHuman}.")
//                 ??? //TODO
//               case RequestResponse.Stream(observable) =>
//                 ??? //TODO
//             }
//
//             //TODO: what about private evnets? scoping?
////             api.publishEvents(apiResponse.action.events)
//
////             serializedResult.transform {
////               case Success(v) => Success(complete(v))
////               //TODO map errors
////               case Failure(err) => Success(complete(StatusCodes.BadRequest -> err.toString))
////             }
//           }
//
////           Right(returnValue)
//           ???
//
//         case RouterResult.Failure(arguments, error) =>
//           scribe.warn(s"http -->[failure] ${requestLogLine(path, arguments, error)}. Took ${watch.readHuman}.")
//           Left(error)
//       }
//     }
//   }

  def fromRouter[PickleType : FromRequestUnmarshaller : ToResponseMarshaller : AsTextMessage](
    router: Router[PickleType, RequestResponse[Unit, HttpErrorCode, ?]],
    config: HttpServerConfig = HttpServerConfig(),
    recoverServerFailure: PartialFunction[ServerFailure, HttpErrorCode] = PartialFunction.empty,
    recoverThrowable: PartialFunction[Throwable, HttpErrorCode] = PartialFunction.empty
  )(implicit scheduler: Scheduler): Route = responseRouterToRoute(router, config, _ => (), recoverServerFailure, recoverThrowable)

  def fromRouterWithState[PickleType : FromRequestUnmarshaller : ToResponseMarshaller : AsTextMessage, State](
    router: Router[PickleType, RequestResponse[State, HttpErrorCode, ?]],
    config: HttpServerConfig = HttpServerConfig(),
    requestToState: HttpRequest => State,
    recoverServerFailure: PartialFunction[ServerFailure, HttpErrorCode] = PartialFunction.empty,
    recoverThrowable: PartialFunction[Throwable, HttpErrorCode] = PartialFunction.empty
  )(implicit scheduler: Scheduler): Route = responseRouterToRoute(router, config, requestToState, recoverServerFailure, recoverThrowable)

  private def responseRouterToRoute[PickleType : FromRequestUnmarshaller : ToResponseMarshaller, State](
    router: Router[PickleType, RequestResponse[State, HttpErrorCode, ?]],
    config: HttpServerConfig,
    requestToState: HttpRequest => State,
    recoverServerFailure: PartialFunction[ServerFailure, HttpErrorCode],
    recoverThrowable: PartialFunction[Throwable, HttpErrorCode]
  )(implicit scheduler: Scheduler, asText: AsTextMessage[PickleType]): Route = {

    (path(Remaining) & post) { pathRest =>
      val watch = StopWatch.started
      val path = pathRest.split("/").toList

      decodeRequest {
        extractRequest { request =>
          entity(as[PickleType]) { entity =>
            router(Request(path, entity)) match {
              case RouterResult.Success(arguments, result) =>
                val response = result match {
                  case result: RequestResponse.Result[State, HttpErrorCode, PickleType] =>
                    result
                  case stateFun: RequestResponse.StateFunction[State, HttpErrorCode, PickleType] =>
                    val state: State = requestToState(request)
                    stateFun.function(state)
                }

                val routeTask = response.value match {
                  case RequestResponse.Single(task) => task.map {
                    case Right(v) =>
                      scribe.info(s"http -->[response] ${requestLogLine(path, arguments, v)}. Took ${watch.readHuman}.")
                      complete(v)
                    case Left(e) =>
                      scribe.info(s"http -->[error] ${requestLogLine(path, arguments, e)}. Took ${watch.readHuman}.")
                      complete(StatusCodes.custom(e.code, e.message))
                  }
                  case RequestResponse.Stream(task) => task.map {
                    case Right(observable) =>
                      val events = observable.map { v =>
                        scribe.info(s"http -->[stream] ${requestLogLine(path, arguments, v)}. Took ${watch.readHuman}.")
                        ServerSentEvent(asText.write(v))
                      }.doOnComplete { () =>
                        scribe.info(s"http -->[stream:complete] ${requestLogLine(path, arguments)}. Took ${watch.readHuman}.")
                      }.doOnError { t =>
                        scribe.warn(s"http -->[stream:error] ${requestLogLine(path, arguments)}. Took ${watch.readHuman}.", t)
                      }
                      val source = Source.fromPublisher(events.toReactivePublisher)
                      complete(source.keepAlive(config.keepAliveInterval, () => ServerSentEvent.heartbeat))
                    case Left(e) =>
                      scribe.info(s"http -->[error] ${requestLogLine(path, arguments, e)}. Took ${watch.readHuman}.")
                      complete(StatusCodes.custom(e.code, e.message))
                  }
                }

                onComplete(routeTask.runAsync) {
                  case Success(r) => r
                  case Failure(t) =>
                    val error = recoverThrowable.lift(t)
                      .fold[StatusCode](StatusCodes.InternalServerError)(e => StatusCodes.custom(e.code, e.message))

                    scribe.info(s"http -->[failure] ${requestLogLine(path, arguments, error)}. Took ${watch.readHuman}.", t)
                    complete(error)
                }
              case RouterResult.Failure(arguments, e) =>
                val error = recoverServerFailure.lift(e)
                  .fold[StatusCode](StatusCodes.BadRequest)(e => StatusCodes.custom(e.code, e.message))

                scribe.warn(s"http -->[failure] ${requestLogLine(path, arguments, s"$e / $error")}. Took ${watch.readHuman}.")
                complete(error)
            }
          }
        }
      }
    }
  }
}
