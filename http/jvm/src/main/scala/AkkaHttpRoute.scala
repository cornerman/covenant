package covenant.http

import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
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

case class HttpServerConfig(bufferSize: Int = 100, overflowStrategy: OverflowStrategy = OverflowStrategy.fail, keepAliveInterval: FiniteDuration = 30 seconds)

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

  //TODO split code, share with/without headers
  private def responseRouterToRoute[PickleType : FromRequestUnmarshaller : ToResponseMarshaller, State](
    router: Router[PickleType, RequestResponse[State, HttpErrorCode, ?]],
    config: HttpServerConfig,
    requestToState: HttpRequest => State,
    recoverServerFailure: PartialFunction[ServerFailure, HttpErrorCode],
    recoverThrowable: PartialFunction[Throwable, HttpErrorCode]
  )(implicit scheduler: Scheduler, asText: AsTextMessage[PickleType]): Route = {
    (path(Remaining) & post) { pathRest =>
      decodeRequest {
        extractRequest { request =>
          val path = pathRest.split("/").toList
          entity(as[PickleType]) { entity =>
            router(Request(path, entity)).toEither match {
              case Right(result) => result match {
                case value: RequestResponse.Value[HttpErrorCode, PickleType] =>
                  runRouteTask(responseToValue(config, value), recoverThrowable)
                case stateFun: RequestResponse.StateFunction[State, HttpErrorCode, PickleType] =>
                  val state: State = requestToState(request)
                  val value = stateFun.function(state)
                  runRouteTask(responseToValue(config, value), recoverThrowable)
                case stateFun: RequestResponse.StateFunction[State, HttpErrorCode, PickleType] =>
                  val state: State = requestToState(request)
                  val value = stateFun.function(state)
                  runRouteTask(responseToValue(config, value), recoverThrowable)
              }
              case Left(e) =>
                val error = recoverServerFailure.lift(e)
                  .fold[StatusCode](StatusCodes.BadRequest)(e => StatusCodes.custom(e.code, e.message))
                complete(error)
            }
          }
        }
      }
    }
  }

  private def runRouteTask[T](task: Task[Route], recoverThrowable: PartialFunction[Throwable, HttpErrorCode])(implicit scheduler: Scheduler): Route = onComplete(task.runAsync) {
    case Success(r) => r
    case Failure(t) =>
      val error = recoverThrowable.lift(t)
        .fold[StatusCode](StatusCodes.InternalServerError)(e => StatusCodes.custom(e.code, e.message))
      complete(error)
  }

  private def responseToValue[PickleType : FromRequestUnmarshaller : ToResponseMarshaller, State](
  config: HttpServerConfig,
  value: RequestResponse.Value[State, HttpErrorCode, PickleType]
  )(implicit scheduler: Scheduler, asText: AsTextMessage[PickleType]): Task[Route] = value match {
    case pureValue: RequestResponse.PureValue[HttpErrorCode, PickleType] => responseToPureValue(config, pureValue)
    case RequestResponse.StateWithValue(state, value) =>
      case Right(observable) =>
        val source = Source.fromPublisher(observable.map(t => ServerSentEvent(asText.write(t))).toReactivePublisher)
        complete(source.keepAlive(config.keepAliveInterval, () => ServerSentEvent.heartbeat))
      case Left(e) => complete(StatusCodes.custom(e.code, e.message))
    }
  }

  private def responseToPureValue[PickleType : FromRequestUnmarshaller : ToResponseMarshaller](
  config: HttpServerConfig,
  value: RequestResponse.PureValue[HttpErrorCode, PickleType]
  )(implicit scheduler: Scheduler, asText: AsTextMessage[PickleType]): Task[Route] = value match {
    case RequestResponse.Single(task) => task.map {
      case Right(v) => complete(v)
      case Left(e) => complete(StatusCodes.custom(e.code, e.message))
    }
    case RequestResponse.Stream(task) => task.map {
      case Right(observable) =>
        val source = Source.fromPublisher(observable.map(t => ServerSentEvent(asText.write(t))).toReactivePublisher)
        complete(source.keepAlive(config.keepAliveInterval, () => ServerSentEvent.heartbeat))
      case Left(e) => complete(StatusCodes.custom(e.code, e.message))
    }
  }

  private def responseToValue[PickleType : FromRequestUnmarshaller : ToResponseMarshaller](
  config: HttpServerConfig,
  value: RequestResponse.Value[HttpErrorCode, PickleType]
  )(implicit scheduler: Scheduler, asText: AsTextMessage[PickleType]): Task[Route] = value match {
    case RequestResponse.Single(task) => task.map {
      case Right(v) => complete(v)
      case Left(e) => complete(StatusCodes.custom(e.code, e.message))
    }
    case RequestResponse.Stream(task) => task.map {
      case Right(observable) =>
        val source = Source.fromPublisher(observable.map(t => ServerSentEvent(asText.write(t))).toReactivePublisher)
        complete(source.keepAlive(config.keepAliveInterval, () => ServerSentEvent.heartbeat))
      case Left(e) => complete(StatusCodes.custom(e.code, e.message))
    }
  }

  //TODO non-state dependent actions will still trigger the state future
  private def requestFunctionToRouteWithHeaders[PickleType : FromRequestUnmarshaller : ToResponseMarshaller](router: (Request[PickleType], HttpRequest) => Either[ServerFailure, Future[Route]]): Route = {
    (path(Remaining) & post) { pathRest =>
      decodeRequest {
        extractRequest { request =>
          val path = pathRest.split("/").toList
          entity(as[PickleType]) { entity =>
            router(Request(path, entity), request) match {
              case Right(result) => onComplete(result) {
                case Success(r) => r
                case Failure(e) => complete(StatusCodes.InternalServerError -> e.toString)
              }
              case Left(err) => complete(StatusCodes.BadRequest -> err.toString)
            }
          }
        }
      }
    }
  }
}
