package covenant.http

import sloth._

import akka.actor.ActorSystem
import akka.util.ByteStringBuilder
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.unmarshalling._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer

import cats.data.EitherT
import cats.implicits._

import scala.concurrent.Future

object HttpClient {
  def apply[PickleType, ErrorType : ClientFailureConvert](
    baseUri: String,
    failedRequestError: (String, StatusCode) => ErrorType,
    recover: PartialFunction[Throwable, ErrorType] = PartialFunction.empty
  )(implicit
    system: ActorSystem,
    materializer: ActorMaterializer,
    unmarshaller: FromByteStringUnmarshaller[PickleType],
    marshaller: ToEntityMarshaller[PickleType]): Client[PickleType, EitherT[Future, ErrorType, ?], ErrorType] = {
    import system.dispatcher

    val transport = new RequestTransport[PickleType, EitherT[Future, ErrorType, ?]] {
      private val sender = sendRequest[PickleType, ErrorType](baseUri, failedRequestError) _
      def apply(request: Request[PickleType]) = EitherT[Future, ErrorType, PickleType] {
        sender(request).recover(recover andThen Left.apply)
      }
    }

    Client[PickleType, EitherT[Future, ErrorType, ?], ErrorType](transport)
  }

  def apply[PickleType](
    baseUri: String
  )(implicit
    system: ActorSystem,
    materializer: ActorMaterializer,
    unmarshaller: FromByteStringUnmarshaller[PickleType],
    marshaller: ToEntityMarshaller[PickleType]): Client[PickleType, Future, ClientException] = {
    import system.dispatcher

    val transport = new RequestTransport[PickleType, Future] {
      private val sender = sendRequest[PickleType, Exception](baseUri, (r,c) => new Exception(s"Http request failed $r: $c")) _
      def apply(request: Request[PickleType]): Future[PickleType] = {
        sender(request).flatMap {
          case Right(res) => Future.successful(res)
          case Left(err) => Future.failed(err)
        }
      }
    }

    Client[PickleType, Future, ClientException](transport)
  }

  private def sendRequest[PickleType, ErrorType](
    baseUri: String,
    failedRequestError: (String, StatusCode) => ErrorType
  )(request: Request[PickleType])(implicit
    system: ActorSystem,
    materializer: ActorMaterializer,
    unmarshaller: FromByteStringUnmarshaller[PickleType],
    marshaller: ToEntityMarshaller[PickleType]) = {
    import system.dispatcher

    val uri = (baseUri :: request.path).mkString("/")
    val entity = Marshal(request.payload).to[MessageEntity]
    scribe.info(s"Outgoing request ($uri)")

    entity.flatMap { entity =>
      Http()
        .singleRequest(HttpRequest(method = HttpMethods.POST, uri = uri, headers = Nil, entity = entity))
        .flatMap { response =>
          scribe.info(s"Got response ($uri): ${response.status}")
          response.status match {
            case StatusCodes.OK =>
              response.entity.dataBytes.runFold(new ByteStringBuilder)(_ append _).flatMap { b =>
                Unmarshal(b.result).to[PickleType].map(Right.apply)
              }
            case code =>
              response.discardEntityBytes()
              Future.successful(Left(failedRequestError(uri, code)))
          }
        }
    }
  }
}
