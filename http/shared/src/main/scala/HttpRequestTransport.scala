package covenant.http

import monix.eval.Task
import monix.reactive.Observable
import cats.MonadError

case class HttpErrorCode(code: Int) extends AnyVal

object HttpRequestTransport extends NativeHttpRequestTransport
