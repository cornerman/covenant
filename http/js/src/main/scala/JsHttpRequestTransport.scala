package covenant.http

import covenant._
import monix.eval.Task
import monix.reactive.Observable
import monix.reactive.subjects.PublishSubject
import org.scalajs.dom
import org.scalajs.dom.crypto.BufferSource
import org.scalajs.dom.experimental.{Request => _, _}
import org.scalajs.dom.raw.EventSource
import sloth._

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js.typedarray.ArrayBuffer

object JsHttpRequestTransport {
  case object DeserializeException extends Exception
  case object EventSourceException extends Exception

  def apply[PickleType](baseUri: String)(implicit
    ec: ExecutionContext,
    asText: AsTextMessage[PickleType],
    builder: JsMessageBuilder[PickleType]
  ) = RequestTransport[PickleType, RequestOperation[HttpErrorCode, ?]] { request =>

    RequestOperation(sendRequest(baseUri, request), sendStreamRequest(baseUri, request).map(Right.apply))
  }

  private def sendRequest[PickleType](baseUri: String, request: Request[PickleType])(implicit
    ec: ExecutionContext,
    builder: JsMessageBuilder[PickleType]
  ): Task[Either[HttpErrorCode, PickleType]] = Task.deferFuture {

    val uri = (baseUri :: request.path).mkString("/")

    val message = builder.pack(request.payload)
    val bodyInit: BodyInit = (message: Any) match {
      case s: String => s
      case a: ArrayBuffer => a.asInstanceOf[BufferSource] //TODO: why does bodyinit not accept ArrayBuffer?
      case b: dom.Blob => b
    }

    //TODO why are var not initialized?
    val response = Fetch.fetch(uri, RequestInit(
      method = HttpMethod.POST,
      body = bodyInit
    ))

    response.toFuture.flatMap { response =>
      if (response.status == 200) response.body.getReader().read().toFuture.flatMap { chunk =>
        val buffer = chunk.value.buffer
        builder.unpack(buffer).flatMap {
          case Some(v) => Future.successful(Right(v))
          case None => Future.failed(DeserializeException)
        }
      } else Future.successful(Left(HttpErrorCode(response.status)))
    }
  }

  //TODO HttpErrorCode?
  private def sendStreamRequest[PickleType](baseUri: String, request: Request[PickleType])(implicit
    asText: AsTextMessage[PickleType]
  ): Observable[PickleType] = Observable.defer {
    val uri = (baseUri :: request.path).mkString("/")
    val source = new EventSource(uri)

    //TODO backpressure, error, complete - is reconnecting?
    val subject = PublishSubject[PickleType]
    source.onerror = { _ =>
      if (source.readyState == EventSource.CLOSED) {
        scribe.warn(s"EventSource got error")
        subject.onError(EventSourceException)
      }
    }
    source.onopen = { _ =>
      scribe.info(s"EventSource opened for url '$uri'")
    }
    source.onmessage = { event =>
      scribe.info(s"EventSource got message: ${event.data}")
      event.data match {
        case s: String =>
          val data = asText.read(s)
          subject.onNext(data)
        case data => scribe.warn(s"Unsupported non-string payload in EventSource message: $data")
      }
    }

    subject
  }
}
