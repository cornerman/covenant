package covenant.http

import covenant._
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import monix.reactive.observables.ConnectableObservable
import monix.reactive.subjects.{ConcurrentSubject, PublishSubject}
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
    scheduler: Scheduler,
    asText: AsTextMessage[PickleType],
    builder: JsMessageBuilder[PickleType]
  ) = RequestTransport[PickleType, RequestOperation[HttpErrorCode, ?]] { request =>

    RequestOperation(
      sendRequest(baseUri, request),
      sendStreamRequest(baseUri, request).map(Right.apply)) //TODO
  }

  private def sendRequest[PickleType](baseUri: String, request: Request[PickleType])(implicit
    ec: ExecutionContext,
    builder: JsMessageBuilder[PickleType]
  ): Task[Either[HttpErrorCode, PickleType]] = Task.deferFuture {

    val uri = (baseUri :: request.path).mkString("/")

    val message = builder.pack(request.payload)
    val bodyInit: BodyInit = (message: Any) match {
      case s: String => s
      case a: ArrayBuffer => a: BufferSource //TODO: why does bodyinit not accept ArrayBuffer?
      case b: dom.Blob => b
    }

    val response = Fetch.fetch(uri, RequestInit(
      method = HttpMethod.POST,
      body = bodyInit
    ))

    response.toFuture.flatMap { response =>
      response.body.getReader().read().toFuture.flatMap { chunk =>
        val buffer = chunk.value.buffer
        if (response.ok) builder.unpack(buffer).flatMap {
          case Some(v) => Future.successful(Right(v))
          case None => Future.failed(DeserializeException)
        } else JsMessageBuilder.JsMessageBuilderString.unpack(buffer).flatMap {
          case Some(v) => Future.successful(Left(HttpErrorCode(response.status, v)))
          case None => Future.failed(DeserializeException)
        }
      }
    }
  }

  //TODO HttpErrorCode?
  //TODO is reconnecting?
  //TODO cancel?
  //TODO close/complete?
  private def sendStreamRequest[PickleType](baseUri: String, request: Request[PickleType])(implicit
    scheduler: Scheduler,
    asText: AsTextMessage[PickleType]
  ): Task[Observable[PickleType]] = Task {
    val uri = (baseUri :: request.path).mkString("/")
    val source = new EventSource(uri)

    val subject = ConcurrentSubject.publish[PickleType]
    val connectObservable = ConnectableObservable.cacheUntilConnect(source = subject, subject = PublishSubject[PickleType]())

    source.onerror = { _ =>
      scribe.warn("EventSource got error")
      if (source.readyState == EventSource.CLOSED) {
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

    connectObservable.doAfterSubscribe { () =>
      connectObservable.connect()
      ()
    }
  }
}
