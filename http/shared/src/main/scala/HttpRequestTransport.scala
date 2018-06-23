package covenant.http

//TODO: we need a typeclass for converting a custom ApiError from/to http error codes
//TODO: extra string message?
case class HttpErrorCode(code: Int) extends AnyVal

object HttpRequestTransport
