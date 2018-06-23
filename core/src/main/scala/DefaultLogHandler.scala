package covenant

import cats.implicits._
import covenant.util._
import sloth.LogHandler
import cats.syntax.monadError._

class DefaultLogHandler[ErrorType] extends LogHandler[RequestOperation[ErrorType, ?]] {
  import LogHelper._

  def logRequest[T](path: List[String], arguments: Product, result: RequestOperation[ErrorType, T]): RequestOperation[ErrorType, T] = {
    val watch = StopWatch.started
    result
     .onError { case error =>
       scribe.error(s"<-- ${requestLogLineError(path, arguments, error)}. Took ${watch.readHuman}.")
       RequestOperation.monadError.pure(())
     }.map { result =>
       scribe.info(s"<-- ${requestLogLine(path, arguments, result)}. Took ${watch.readHuman}.")
       result
     }
  }
}

object DefaultLogHandler extends  {
  def apply[ErrorType]: LogHandler[RequestOperation[ErrorType, ?]] = new DefaultLogHandler
}
