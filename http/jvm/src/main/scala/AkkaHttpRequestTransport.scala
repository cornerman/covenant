package covenant.http

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.unmarshalling.{Unmarshal, _}
import akka.http.scaladsl.unmarshalling.sse.EventStreamUnmarshalling._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.util.ByteStringBuilder
import cats.data.EitherT
import covenant._
import monix.eval.Task
import monix.reactive.Observable
import monix.reactive.subjects.PublishSubject
import sloth._

import scala.concurrent.Future
import scala.util.{Failure, Success}

object AkkaHttpRequestTransport {
  def apply[PickleType](baseUri: String)(implicit
    system: ActorSystem,
    asText: AsTextMessage[PickleType],
    materializer: ActorMaterializer,
    unmarshaller: FromByteStringUnmarshaller[PickleType],
    marshaller: ToEntityMarshaller[PickleType]) = RequestTransport[PickleType, EitherT[RequestOperation, HttpErrorCode, ?]] { request =>

    EitherT(RequestOperation {
      case RequestKind.Single => Observable.fromTask(sendRequest(baseUri, request))
      case RequestKind.Stream => sendStreamRequest(baseUri, request)
    })
  }

  private def sendRequest[PickleType](baseUri: String, request: Request[PickleType])(implicit
    system: ActorSystem,
    materializer: ActorMaterializer,
    unmarshaller: FromByteStringUnmarshaller[PickleType],
    marshaller: ToEntityMarshaller[PickleType]): Task[Either[HttpErrorCode, PickleType]] = Task.fromFuture {
    import system.dispatcher

    val uri = (baseUri :: request.path).mkString("/")
    val entity = Marshal(request.payload).to[MessageEntity]

    entity.flatMap { entity =>
      Http()
        .singleRequest(HttpRequest(method = HttpMethods.POST, uri = uri, headers = Nil, entity = entity))
        .flatMap { response =>
          response.status match {
            case StatusCodes.OK =>
              response.entity.dataBytes.runFold(new ByteStringBuilder)(_ append _).flatMap { b =>
                Unmarshal(b.result).to[PickleType]
              }.map(Right.apply)
            case code =>
              response.discardEntityBytes()
              Future.successful(Left(HttpErrorCode(code.intValue)))
          }
        }
    }
  }

  //TODO lazy observable
  private def sendStreamRequest[PickleType](baseUri: String, request: Request[PickleType])(implicit
    system: ActorSystem,
    materializer: ActorMaterializer,
    asText: AsTextMessage[PickleType],
    unmarshaller: FromByteStringUnmarshaller[PickleType],
    marshaller: ToEntityMarshaller[PickleType]): Observable[Either[HttpErrorCode, PickleType]] = {
    import system.dispatcher

    val uri = (baseUri :: request.path).mkString("/")
    val entity = Marshal(request.payload).to[MessageEntity]

    val subject = PublishSubject[Either[HttpErrorCode, PickleType]]
    entity.foreach { entity =>
      val requested = Http()
        .singleRequest(HttpRequest(method = HttpMethods.POST, uri = uri, headers = Nil, entity = entity))
        .flatMap { response =>
          response.status match {
            case StatusCodes.OK =>
              Unmarshal(response).to[Source[ServerSentEvent, NotUsed]].map(_.map(Right.apply))
            case code =>
              response.discardEntityBytes()
              Future.successful(Source.apply(List(Left(HttpErrorCode(code.intValue())))))
          }
        }

      //TODO error, backpressure protocol
      requested.onComplete {
        case Success(source) => source.runForeach {
          case Right(value) =>
            val pickled = asText.read(value.data)
            subject.onNext(Right(pickled))
          case Left(err) =>
            subject.onNext(Left(err))
        }.onComplete {
          case Success(done) => subject.onComplete()
          case Failure(err) => subject.onError(err)
        }
        case Failure(err) => subject.onError(err)
      }
    }
    subject
  }

}
