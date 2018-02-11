package covenant.http

import sloth.core._

import akka.util.ByteString
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.unmarshalling._
import akka.http.scaladsl.model._
import akka.stream.Materializer

import cats.data.EitherT
import cats.syntax.either._

import java.nio.ByteBuffer
import scala.concurrent.{Future, ExecutionContext}
import scala.util.{Success, Failure}

trait AkkaHttpRouteImplicits {

  implicit val ByteBufferUnmarshaller: FromByteStringUnmarshaller[ByteBuffer] = new FromByteStringUnmarshaller[ByteBuffer] {
    def apply(value: ByteString)(implicit ec: ExecutionContext, materializer: Materializer): Future[java.nio.ByteBuffer] =
      Future.successful(value.asByteBuffer)
  }

  implicit val ByteBufferEntityUnmarshaller: FromEntityUnmarshaller[ByteBuffer] = Unmarshaller.byteStringUnmarshaller.andThen(ByteBufferUnmarshaller)
  implicit val ByteBufferEntityMarshaller: ToEntityMarshaller[ByteBuffer] = Marshaller.ByteStringMarshaller.compose(ByteString(_))

  implicit class HttpRouterFuture[PickleType](val router: Router[PickleType, Future]) {
    def asHttpRoute(implicit ec: ExecutionContext, u: FromRequestUnmarshaller[PickleType], m: ToResponseMarshaller[PickleType]): Route = {
      requestFunctionToRoute[PickleType](r => router(r).toEither.map(_.map(complete(_))))
    }
  }

  implicit class HttpRouterEitherT[PickleType, ErrorType](val router: Router[PickleType, EitherT[Future, ErrorType, ?]]) {
    def asHttpRoute(implicit ec: ExecutionContext, u: FromRequestUnmarshaller[PickleType], m: ToResponseMarshaller[PickleType], em: ToResponseMarshaller[ErrorType]): Route = {
      requestFunctionToRoute[PickleType](r => router(r).toEither.map(_.value.map(_.fold(complete(_), complete(_)))))
    }
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
}
