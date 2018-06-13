package covenant.core

import sloth.LogHandler
import covenant.core.util._
import cats.implicits._
import cats.syntax.monadError._

import scala.concurrent.{Future, ExecutionContext}

object DefaultLogHandler extends LogHandler {
  import LogHelper._

  override def logRequest[Result[_], ErrorType](path: List[String], arguments: Product, result: Result[_])(implicit monad: cats.MonadError[Result, _ >: ErrorType]): Unit = {
    val watch = StopWatch.started
    scribe.info(s"--> ${requestLogLine(path, arguments)}.")
    monad.map(result) { result =>
      scribe.info(s"<-- ${requestLogLine(path, arguments, result)}. Took ${watch.readHuman}.")
    }
    monad.onError(result) { case err =>
      scribe.error(s"<-- ${requestLogLineError(path, arguments, result)}. Took ${watch.readHuman}.")
      monad.pure(())
    }
  }
}
