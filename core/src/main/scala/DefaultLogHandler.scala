package covenant.core

import sloth.{LogHandler, MonadClientFailure}
import covenant.core.util._
import cats.implicits._
import cats.syntax.monadError._
import cats.MonadError

import scala.concurrent.{Future, ExecutionContext}

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
