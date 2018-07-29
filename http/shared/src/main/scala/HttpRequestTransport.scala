package covenant.http

import covenant.RequestOperation
import monix.execution.{Cancelable, Scheduler}
import sloth.{Request, RequestTransport}

//TODO: extra string message?
case class HttpErrorCode(code: Int, message: String)
case class HttpException(code: HttpErrorCode) extends Exception(code.toString)
