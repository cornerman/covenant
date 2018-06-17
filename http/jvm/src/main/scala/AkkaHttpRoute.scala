package covenant.http

import java.nio.ByteBuffer

import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.unmarshalling._
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.util.ByteString
import covenant._
import covenant.api._
import covenant.util.StopWatch
import covenant.http.api._
import monix.execution.Scheduler
import sloth._

import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

case class HttpServerConfig(bufferSize: Int = 100, overflowStrategy: OverflowStrategy = OverflowStrategy.fail, keepAliveInterval: FiniteDuration = 30 seconds)

//TODO: use without need to import?
object ByteBufferImplicits {
  implicit val ByteBufferUnmarshaller: FromByteStringUnmarshaller[ByteBuffer] = new FromByteStringUnmarshaller[ByteBuffer] {
    def apply(value: ByteString)(implicit ec: ExecutionContext, materializer: Materializer): Future[java.nio.ByteBuffer] =
      Future.successful(value.asByteBuffer)
  }

  implicit val ByteBufferEntityUnmarshaller: FromEntityUnmarshaller[ByteBuffer] = Unmarshaller.byteStringUnmarshaller.andThen(ByteBufferUnmarshaller)
  implicit val ByteBufferEntityMarshaller: ToEntityMarshaller[ByteBuffer] = Marshaller.ByteStringMarshaller.compose(ByteString(_))
}

object AkkaHttpRoute {
   import covenant.util.LogHelper._

   def fromApiRouter[PickleType : FromRequestUnmarshaller : ToResponseMarshaller, Event, ErrorType, State](
     router: Router[PickleType, RawServerDsl.ApiFunctionT[Event, State, ?]],
     api: HttpApiConfiguration[Event, ErrorType, State])(implicit
     scheduler: Scheduler): Route = {

     requestFunctionToRouteWithHeaders[PickleType] { (r, httpRequest) =>
       val watch = StopWatch.started
       val state: Future[State] = api.requestToState(httpRequest)
       val path = httpRequest.getUri.toString.split("/").toList //TODO

       router(r) match {
         case RouterResult.Success(arguments, apiFunction) => apiFunction match {
           case f: RawServerDsl.ApiFunction.Single[Event, State, RouterResult.Value[PickleType]] =>
             val apiResponse = f.run(state)

             val returnValue = {
               val future = apiResponse.action.value
               val events = apiResponse.action.events
               val rawResult = future.map(_.raw)
               val serializedResult = future.map(_.serialized)
               scribe.info(s"http -->[response] ${requestLogLine(path, arguments, rawResult)} / ${events}. Took ${watch.readHuman}.")

               //TODO: what about private evnets? scoping?
               api.publishEvents(apiResponse.action.events)

               serializedResult.transform {
                 case Success(v) => Success(complete(v))
                 //TODO map errors
                 case Failure(err) => Success(complete(StatusCodes.BadRequest -> err.toString))
               }
             }

             Right(returnValue)

           case f: RawServerDsl.ApiFunction.Stream[Event, State, RouterResult.Value[PickleType]] => ??? // TODO
         }

         case RouterResult.Failure(arguments, error) =>
           scribe.warn(s"http -->[failure] ${requestLogLine(path, arguments, error)}. Took ${watch.readHuman}.")
           Left(error)
       }
     }
   }

  def fromRouter[PickleType : FromRequestUnmarshaller : ToResponseMarshaller : AsTextMessage](
    router: Router[PickleType, RequestResponse],
    config: HttpServerConfig = HttpServerConfig(),
    recoverServerFailure: PartialFunction[ServerFailure, HttpErrorCode] = PartialFunction.empty,
    recoverThrowable: PartialFunction[Throwable, HttpErrorCode] = PartialFunction.empty
  )(implicit scheduler: Scheduler): Route = responseRouterToRoute[PickleType](router, config, recoverServerFailure, recoverThrowable)

  //TODO split code, share with/without headers
  private def responseRouterToRoute[PickleType : FromRequestUnmarshaller : ToResponseMarshaller](
    router: Router[PickleType, RequestResponse],
    config: HttpServerConfig,
    recoverServerFailure: PartialFunction[ServerFailure, HttpErrorCode],
    recoverThrowable: PartialFunction[Throwable, HttpErrorCode]
  )(implicit scheduler: Scheduler, asText: AsTextMessage[PickleType]): Route = {
    (path(Remaining) & post) { pathRest =>
      decodeRequest {
        val path = pathRest.split("/").toList
        entity(as[PickleType]) { entity =>
          router(Request(path, entity)).toEither match {
            case Right(result) => result match {
              case RequestResponse.Single(task) => onComplete(task.runAsync) {
                case Success(r) => complete(r)
                case Failure(e) =>
                  val error = recoverThrowable.lift(e)
                    .fold[StatusCode](StatusCodes.InternalServerError)(e => StatusCode.int2StatusCode(e.code))
                  complete(error)
              }
              case RequestResponse.Stream(observable) =>
                val (outgoing, outgoingMaterialized) = {
                  val promise = Promise[SourceQueueWithComplete[ServerSentEvent]]
                  val source = Source.queue[ServerSentEvent](config.bufferSize, config.overflowStrategy)
                                    .mapMaterializedValue { m => promise.success(m); m }
                  (source, promise.future)
                }
                outgoingMaterialized.onComplete {
                  case Success(queue) =>
                    //TODO error, backpressure protocol
                    observable //TODO cancel subscription?
                      .map(asText.write)
                      .doOnComplete(() => queue.complete())
                      .doOnError(t => queue fail t) //TODO: sent error code?
                      .foreach(text => queue offer ServerSentEvent(text))
                  case Failure(e) => ??? //TODO error code?
                }

                complete(outgoing.keepAlive(config.keepAliveInterval, () => ServerSentEvent.heartbeat))
            }
            case Left(e) =>
              val error = recoverServerFailure.lift(e)
                .fold[StatusCode](StatusCodes.BadRequest)(e => StatusCode.int2StatusCode(e.code))
              complete(error)
          }
        }
      }
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
