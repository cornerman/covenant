package covenant

import cats.MonadError
import cats.implicits._
import covenant.util._
import sloth.LogHandler

object DefaultLogHandler extends LogHandler {
  import LogHelper._

  def logRequest[Result[_], T](path: List[String], arguments: Product, result: Result[T])(implicit monad: MonadError[Result, _]): Result[T] = {
    val watch = StopWatch.started
    monad.onError(result) { case error =>
      scribe.error(s"<-- ${requestLogLineError(path, arguments, error)}. Took ${watch.readHuman}.")
      monad.pure(())
    }.map { result =>
      scribe.info(s"<-- ${requestLogLine(path, arguments, result)}. Took ${watch.readHuman}.")
      result
    }
  }
}
