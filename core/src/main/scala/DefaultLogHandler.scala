package covenant

import cats.MonadError
import covenant.util._
import sloth.LogHandler
import cats.implicits._
import monix.eval.Task

class DefaultLogHandler[ErrorType] extends LogHandler[RequestOperation[ErrorType, ?]] {
  import LogHelper._

  def logRequest[T](path: List[String], arguments: Product, result: RequestOperation[ErrorType, T]): RequestOperation[ErrorType, T] = {
    val watch = StopWatch.started
    RequestOperation(
      result.single.map {
        case Right(result) =>
          scribe.info(s"<--[success] ${requestLogLine(path, arguments, result)}. Took ${watch.readHuman}.")
          Right(result)
        case Left(error) =>
          scribe.error(s"<--[error] ${requestLogLine(path, arguments, error)}. Took ${watch.readHuman}.")
          Left(error)
      }.onError { case t =>
        scribe.error(s"<--[exception] ${requestLogLine(path, arguments, t)}. Took ${watch.readHuman}.")
        Task.pure(())
      },
      result.stream.map {
        case Right(obs) => Right(obs.doOnNext { result =>
          scribe.info(s"<--[stream:success] ${requestLogLine(path, arguments, result)}. Took ${watch.readHuman}.")
        })
        case Left(error) =>
          scribe.error(s"<--[stream:error] ${requestLogLine(path, arguments, error)}. Took ${watch.readHuman}.")
          Left(error)
      }.onError { case t =>
        scribe.error(s"<--[stream:exception] ${requestLogLine(path, arguments, t)}. Took ${watch.readHuman}.")
        Task.pure(())
      }
    )
  }
}

object DefaultLogHandler extends  {
  def apply[ErrorType]: LogHandler[RequestOperation[ErrorType, ?]] = new DefaultLogHandler[ErrorType]
}
