package covenant.http

import covenant.RequestOperation
import monix.eval.Task
import monix.execution.cancelables.CompositeCancelable
import monix.execution.{Cancelable, Scheduler}
import monix.reactive.Observable
import sloth.{Request, RequestTransport}

case class HttpErrorCode(code: Int, message: String)
case class HttpHeader(name: String, value: String)

abstract class HttpRequestTransport[PickleType](implicit scheduler: Scheduler) extends RequestTransport[PickleType, RequestOperation[HttpErrorCode, ?]] with Cancelable {
  private val cancelable = CompositeCancelable()
  private val defaultTransport = requestWith(Observable.now(Nil))

  def apply(request: Request[PickleType]): RequestOperation[HttpErrorCode, PickleType] = defaultTransport(request)

  def requestWith(headers: Observable[List[HttpHeader]]): RequestTransport[PickleType, RequestOperation[HttpErrorCode, ?]] = {

    val headersWithLast = headers.replay(1)
    cancelable += headersWithLast.connect()

    RequestTransport { request =>
      val currentHeaders = headersWithLast.headL
      RequestOperation(currentHeaders.flatMap(requestSingle(request, _)), currentHeaders.flatMap(requestStream(request, _)))
    }
  }

  protected def requestSingle(request: Request[PickleType], headers: List[HttpHeader]): Task[Either[HttpErrorCode, PickleType]]
  protected def requestStream(request: Request[PickleType], headers: List[HttpHeader]): Task[Either[HttpErrorCode, Observable[PickleType]]]

  def cancel(): Unit = cancelable.cancel()
}

object HttpRequestTransport {
  //TODO cancelable for event streams?
  def apply[PickleType](single: (Request[PickleType], List[HttpHeader]) => Task[Either[HttpErrorCode, PickleType]], stream: (Request[PickleType], List[HttpHeader]) => Task[Either[HttpErrorCode, Observable[PickleType]]])(implicit scheduler: Scheduler) = new HttpRequestTransport[PickleType] {
    override protected def requestSingle(request: Request[PickleType], headers: List[HttpHeader]) = single(request, headers)
    override protected def requestStream(request: Request[PickleType], headers: List[HttpHeader]) = stream(request, headers)
  }
}
