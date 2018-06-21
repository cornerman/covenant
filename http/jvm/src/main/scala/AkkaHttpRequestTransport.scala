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
    marshaller: ToEntityMarshaller[PickleType]) = RequestTransport[PickleType, EitherT[RequestOperation[HttpErrorCode, ?], HttpErrorCode, ?]] { request =>

//    EitherT(RequestOperation(sendRequest(baseUri, request), sendStreamRequest(baseUri, request)))
    ???
  }

  // TODO: unify both send methods and branch in response?
  private def sendRequest[PickleType](baseUri: String, request: Request[PickleType])(implicit
    system: ActorSystem,
    materializer: ActorMaterializer,
    unmarshaller: FromByteStringUnmarshaller[PickleType],
    marshaller: ToEntityMarshaller[PickleType]): Task[Either[HttpErrorCode, PickleType]] = Task.deferFuture {
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

  private def sendStreamRequest[PickleType](baseUri: String, request: Request[PickleType])(implicit
    system: ActorSystem,
    materializer: ActorMaterializer,
    asText: AsTextMessage[PickleType],
    marshaller: ToEntityMarshaller[PickleType]): Task[Either[HttpErrorCode, Observable[PickleType]]] = Task.deferFuture {
    import system.dispatcher

    val uri = (baseUri :: request.path).mkString("/")
    val entity = Marshal(request.payload).to[MessageEntity]

    entity.flatMap { entity =>
      val requested: Future[Either[HttpErrorCode, Source[ServerSentEvent, NotUsed]]] = Http()
        .singleRequest(HttpRequest(method = HttpMethods.POST, uri = uri, headers = Nil, entity = entity))
        .flatMap { response =>
          response.status match {
            case StatusCodes.OK =>
              Unmarshal(response).to[Source[ServerSentEvent, NotUsed]].map(Right.apply)
            case code =>
              response.discardEntityBytes()
              Future.successful(Left(HttpErrorCode(code.intValue())))
          }
        }

      //TODO error, backpressure protocol
      requested.map(_.map { source =>
        val subject = PublishSubject[PickleType]
        source.runForeach { value =>
          val pickled = asText.read(value.data)
          subject.onNext(pickled)
        }.onComplete {
          case Success(_) => subject.onComplete()
          case Failure(err) => subject.onError(err)
        }
        subject
      })
    }
  }
}
