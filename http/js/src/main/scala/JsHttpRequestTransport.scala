package covenant.http

import cats.data.EitherT
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
import scala.scalajs.js
import scala.scalajs.js.typedarray.ArrayBuffer

object JsHttpRequestTransport {
  case object DeserializeException extends Exception
  case object EventSourceException extends Exception

  def apply[PickleType](baseUri: String)(implicit
    ec: ExecutionContext,
    builder: JsMessageBuilder[PickleType]
  ) = RequestTransport[PickleType, EitherT[RequestOperation, HttpErrorCode, ?]] { request =>

    EitherT(RequestOperation {
      case RequestKind.Single => Observable.fromTask(sendRequest(baseUri, request))
      case RequestKind.Stream => sendStreamRequest(baseUri, request)
    })
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
    val response = Fetch.fetch(uri, new RequestInit {
      var method: js.UndefOr[HttpMethod] = HttpMethod.POST
      var headers: js.UndefOr[HeadersInit] = js.undefined
      var body: js.UndefOr[BodyInit] = bodyInit
      var referrer: js.UndefOr[String] = js.undefined
      var referrerPolicy: js.UndefOr[ReferrerPolicy] = js.undefined
      var mode: js.UndefOr[RequestMode] = js.undefined
      var credentials: js.UndefOr[RequestCredentials] = js.undefined
      var requestCache: js.UndefOr[RequestCache] = js.undefined
      var requestRedirect: js.UndefOr[RequestRedirect] = js.undefined
      var integrity: js.UndefOr[String] = js.undefined
      var window: js.UndefOr[Null] = js.undefined
    })

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

  private def sendStreamRequest[PickleType](baseUri: String, request: Request[PickleType])(implicit
    ec: ExecutionContext,
    builder: JsMessageBuilder[PickleType]
  ): Observable[Either[HttpErrorCode, PickleType]] = Observable.defer {
    val uri = (baseUri :: request.path).mkString("/")
    val source = new EventSource(uri)
    val subject = PublishSubject[Either[HttpErrorCode, PickleType]]
    source.onerror = { event =>
      scribe.warn(s"EventSource got error")
      subject.onError(EventSourceException)
    }
    source.onopen = { _ =>
      scribe.info(s"EventSource opened for url '$uri'")
    }
    source.onmessage = { event =>
      scribe.info(s"EventSource got message: ${event.data}")
      val msg = event.data match {
        case s: String => builder.unpack(s)
        case a: ArrayBuffer => builder.unpack(a)
        case b: dom.Blob => builder.unpack(b)
      }
      //TODO order
      msg.foreach {
        case Some(v) => subject.onNext(Right(v))
        case None => subject.onError(DeserializeException)
      }
    }

    subject
  }
}
