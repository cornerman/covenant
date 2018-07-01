package covenant.http

//TODO: extra string message?
case class HttpErrorCode(code: Int, message: String)
case class HttpException(code: HttpErrorCode) extends Exception(code.toString)

trait HttpErrorCodeConvert[+T] {
  def convert(failure: HttpErrorCode): T
}
object HttpErrorCodeConvert {
  def apply[T](f: HttpErrorCode => T) = new HttpErrorCodeConvert[T] {
    def convert(failure: HttpErrorCode): T = f(failure)
  }

  implicit def ToHttpErrorCode: HttpErrorCodeConvert[HttpErrorCode] = HttpErrorCodeConvert[HttpErrorCode](identity)
  implicit def ToClientException: HttpErrorCodeConvert[HttpException] = HttpErrorCodeConvert[HttpException](HttpException(_))
}

object HttpRequestTransport
