package covenant

import cats.MonadError
import covenant.util._
import sloth.LogHandler
import cats.implicits._

class DefaultLogHandler[R[_], ErrorType](implicit monadError: MonadError[R, ErrorType]) extends LogHandler[R] {
  import LogHelper._

  def logRequest[T](path: List[String], arguments: Product, result: R[T]): R[T] = {
    val watch = StopWatch.started
   result.onError { case error =>
     scribe.error(s"<-- ${requestLogLineError(path, arguments, error)}. Took ${watch.readHuman}.")
     monadError.pure(())
   }.map { result =>
     scribe.info(s"<-- ${requestLogLine(path, arguments, result)}. Took ${watch.readHuman}.")
     result
   }
  }
}

object DefaultLogHandler extends  {
  def apply[R[_], ErrorType](implicit monadError: MonadError[R, ErrorType]): LogHandler[R] = new DefaultLogHandler[R, ErrorType]
}
