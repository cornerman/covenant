package covenant

import cats.MonadError
import covenant.util._
import sloth.LogHandler
import cats.implicits._

class DefaultLogHandler[R[_], ErrorType](implicit monadError: MonadError[R, ErrorType]) extends LogHandler[R] {
  import LogHelper._

  //TODO: what about throwables in the monad? they will occur and are not logged => implement on requestoperation
  //sloth: should it just require a monaderror[R, Throwable] to and just forward the deserialize exception?
  def logRequest[T](path: List[String], arguments: Product, result: R[T]): R[T] = {
    val watch = StopWatch.started
   result.onError { case error =>
     scribe.error(s"<--[error] ${requestLogLine(path, arguments, error)}. Took ${watch.readHuman}.")
     monadError.pure(())
   }.map { result =>
     scribe.info(s"<--[success] ${requestLogLine(path, arguments, result)}. Took ${watch.readHuman}.")
     result
   }
  }
}

object DefaultLogHandler extends  {
  def apply[R[_], ErrorType](implicit monadError: MonadError[R, ErrorType]): LogHandler[R] = new DefaultLogHandler[R, ErrorType]
}
