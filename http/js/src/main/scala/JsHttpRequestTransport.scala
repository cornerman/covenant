package covenant.http

import cats.data.EitherT
import covenant._
import monix.eval.Task
import monix.reactive.Observable
import org.scalajs.dom
import sloth._

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js.typedarray.ArrayBuffer
import scala.util.Try


object JsHttpRequestTransport {
  case object DeserializeException extends Exception

  def apply[PickleType](
    baseUri: String)(implicit
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
    builder: JsMessageBuilder[PickleType]): Task[Either[HttpErrorCode, PickleType]] = Task.fromFuture {

    val uri = (baseUri :: request.path).mkString("/")
    val promise = Promise[Either[HttpErrorCode, PickleType]]()

    //TODO use fetch? can be intercepted by serviceworker.
    val http = new dom.XMLHttpRequest
    http.responseType = builder.responseType

    http.open("POST", uri, true)
    http.onreadystatechange = { (_: dom.Event) =>
      if(http.readyState == 4)
        if (http.status == 200) {
          val value = (http.response: Any) match {
            case s: String => builder.unpack(s)
            case a: ArrayBuffer => builder.unpack(a)
            case b: dom.Blob => builder.unpack(b)
            case _ => Future.successful(None)
          }

          promise completeWith value.flatMap {
            case Some(v) => Future.successful(Right(v))
            case None => Future.failed(DeserializeException)
          }
        }
        else promise trySuccess Left(HttpErrorCode(http.status))
    }

    val message = builder.pack(request.payload)
    (message: Any) match {
      case s: String => Try(http.send(s))
      case a: ArrayBuffer => Try(http.send(a))
      case b: dom.Blob => Try(http.send(b))
    }

    promise.future
  }

  private def sendStreamRequest[PickleType](baseUri: String, request: Request[PickleType]): Observable[Either[HttpErrorCode, PickleType]] = ???
}
