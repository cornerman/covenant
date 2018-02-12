package covenant.core

import sloth.LogHandler
import covenant.core.util._

import scala.concurrent.{Future, ExecutionContext}

class DefaultLogHandler[Result[_]](f: Result[_] => Future[Any])(implicit ec: ExecutionContext) extends LogHandler[Result] {
  import LogHelper._

  override def logRequest(path: List[String], arguments: List[List[Any]], result: Result[_]): Unit = {
    val watch = StopWatch.started
    scribe.info(s"--> ${requestLogLine(path, arguments)}.")
    f(result).onComplete { result =>
      scribe.info(s"<-- ${requestLogLine(path, arguments, result)}. Took ${watch.readHuman}.")
    }
  }
}
