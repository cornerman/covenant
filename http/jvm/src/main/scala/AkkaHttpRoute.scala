package covenant.http

import sloth._
import covenant.core.util.StopWatch
import covenant.core.api._
import covenant.http.api._
import akka.util.ByteString
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.unmarshalling._
import akka.http.scaladsl.model._
import akka.stream.Materializer
import monix.execution.Scheduler
import cats.data.EitherT
import cats.syntax.either._
import java.nio.ByteBuffer

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object ByteBufferImplicits {
  implicit val ByteBufferUnmarshaller: FromByteStringUnmarshaller[ByteBuffer] = new FromByteStringUnmarshaller[ByteBuffer] {
    def apply(value: ByteString)(implicit ec: ExecutionContext, materializer: Materializer): Future[java.nio.ByteBuffer] =
      Future.successful(value.asByteBuffer)
  }

  implicit val ByteBufferEntityUnmarshaller: FromEntityUnmarshaller[ByteBuffer] = Unmarshaller.byteStringUnmarshaller.andThen(ByteBufferUnmarshaller)
  implicit val ByteBufferEntityMarshaller: ToEntityMarshaller[ByteBuffer] = Marshaller.ByteStringMarshaller.compose(ByteString(_))
}

object AkkaHttpRoute {
   import covenant.core.util.LogHelper._

   def fromApiRouter[PickleType : FromRequestUnmarshaller : ToResponseMarshaller, Event, ErrorType, State, Result[_]](
     router: Router[PickleType, Result],
     api: HttpApiConfiguration[Event, ErrorType, State])(implicit
     scheduler: Scheduler,
     env: Result[_] <:< ApiDsl[Event, ErrorType, State]#ApiFunctionT[_]): Route = {
     import api.dsl._

     requestFunctionToRouteWithHeaders[PickleType] { (r, httpRequest) =>
       val watch = StopWatch.started
       val state: Future[State] = api.requestToState(httpRequest)
       val path = httpRequest.getUri.toString.split("/").toList //TODO

       //TODO: asinstanceof
       router(r).asInstanceOf[RouterResult[PickleType, ApiDsl[Event, ErrorType, State]#ApiFunctionT]] match {
         case RouterResult.Success(arguments, apiFunction) => apiFunction match {
           case f: ApiFunction.Single[RouterResult.Value[PickleType]] =>
             val apiResponse = f.run(state)
             val newState = apiResponse.state

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

           case f: ApiFunction.Stream[RouterResult.Value[PickleType]] => ??? // TODO
         }

         case RouterResult.Failure(arguments, error) =>
           scribe.warn(s"http -->[failure] ${requestLogLine(path, arguments, error)}. Took ${watch.readHuman}.")
           Left(error)
       }
     }
   }

  def fromFutureRouter[PickleType : FromRequestUnmarshaller : ToResponseMarshaller](router: Router[PickleType, Future])(implicit ec: ExecutionContext): Route = {
    requestFunctionToRoute[PickleType](r => router(r).toEither.map(_.map(complete(_))))
  }

  def fromEitherTRouter[PickleType : FromRequestUnmarshaller : ToResponseMarshaller, ErrorType : ToResponseMarshaller](router: Router[PickleType, EitherT[Future, ErrorType, ?]])(implicit ec: ExecutionContext): Route = {
    requestFunctionToRoute[PickleType](r => router(r).toEither.map(_.value.map(_.fold(complete(_), complete(_)))))
  }

  private def requestFunctionToRoute[PickleType : FromRequestUnmarshaller : ToResponseMarshaller](router: Request[PickleType] => Either[ServerFailure, Future[Route]]): Route = {
    (path(Remaining) & post) { pathRest =>
      decodeRequest {
        val path = pathRest.split("/").toList
        entity(as[PickleType]) { entity =>
          router(Request(path, entity)) match {
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

  //TODO share code with/without headers
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
