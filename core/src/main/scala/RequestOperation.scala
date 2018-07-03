package covenant

import cats.data.EitherT
import cats.implicits._
import cats.{MonadError, ~>}
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import sloth._

import scala.concurrent.Future

case class RequestOperation[+ErrorType, +T](single: Task[Either[ErrorType, T]], stream: Task[Either[ErrorType, Observable[T]]])
object RequestOperation {

  implicit def clientResultError[ErrorType]: ClientResultErrorT[RequestOperation[ErrorType, ?], Throwable] = new ClientResultErrorT[RequestOperation[ErrorType, ?], Throwable] {
    def mapMaybe[T, R](result: RequestOperation[ErrorType, T])(f: T => Either[Throwable, R]) = RequestOperation[ErrorType, R](
      result.single.flatMap {
        case Right(v) => f(v) match {
          case Right(v) => Task.pure(Right(v))
          case Left(ex) => Task.raiseError(ex)
        }
        case Left(err) => Task.pure(Left(err))
      },
      result.stream.flatMap {
        case Right(o) => Task.pure(Right(o.flatMap(f andThen {
          case Right(v) => Observable.pure(v)
          case Left(ex) => Observable.raiseError(ex)
        })))
        case Left(err) => Task.pure(Left(err))
      }
    )
    def raiseError[T](ex: Throwable) = RequestOperation(Task.raiseError(ex), Task.raiseError(ex))
  }

  implicit def toTaskEitherT[ErrorType]: ResultMapping[RequestOperation[ErrorType, ?], EitherT[Task, ErrorType, ?]] = ResultMapping(Lambda[RequestOperation[ErrorType, ?] ~> EitherT[Task, ErrorType, ?]](op => EitherT(op.single)))
  implicit def toFutureEitherT[ErrorType](implicit scheduler: Scheduler): ResultMapping[RequestOperation[ErrorType, ?], EitherT[Future, ErrorType, ?]] = ResultMapping(Lambda[RequestOperation[ErrorType, ?] ~> EitherT[Future, ErrorType, ?]](op => EitherT(op.single.runAsync)))

  type EitherTObservable[F[_], A, B] = EitherT[F, A, Observable[B]]
  implicit def toTaskEitherTObservable[ErrorType]: ResultMapping[RequestOperation[ErrorType, ?], EitherTObservable[Task, ErrorType, ?]] = ResultMapping[RequestOperation[ErrorType, ?], EitherTObservable[Task, ErrorType, ?]](Lambda[RequestOperation[ErrorType, ?] ~> EitherTObservable[Task, ErrorType, ?]](op => EitherT(op.stream)))
  implicit def toFutureEitherTObservable[ErrorType](implicit scheduler: Scheduler): ResultMapping[RequestOperation[ErrorType, ?], EitherTObservable[Future, ErrorType, ?]] = ResultMapping[RequestOperation[ErrorType, ?], EitherTObservable[Future, ErrorType, ?]](Lambda[RequestOperation[ErrorType, ?] ~> EitherTObservable[Future, ErrorType, ?]](op => EitherT(op.stream.runAsync)))

  implicit def toTask[ErrorType]: ResultMapping[RequestOperation[ErrorType, ?], Task] = toTaskEitherT.mapK(flattenEitherT[Task, ErrorType])
  implicit def toFuture[ErrorType](implicit scheduler: Scheduler): ResultMapping[RequestOperation[ErrorType, ?], Future] = toFutureEitherT.mapK(flattenEitherT[Future, ErrorType])
  implicit def toObservable[ErrorType]: ResultMapping[RequestOperation[ErrorType, ?], Observable] = toTaskEitherTObservable.mapK(flattenEitherTObservable[ErrorType])

  //TODO: does not resolve?
  //    implicit def toF[F[_], ErrorType](implicit monad: MonadError[F, Throwable]): ResultMapping[EitherT[F, ErrorType, ?], F] = ResultMapping(flattenEitherT[F, ErrorType])
  //    implicit def toFromTo[From[_], To[_], ErrorType](implicit from: ResultMapping[RequestOperation[ErrorType, ?], EitherT[From, ErrorType, ?]], to: ResultMapping[EitherT[From, ErrorType, ?], To]): ResultMapping[RequestOperation[ErrorType, ?], To] = from.mapK(to)
  //TODO makes compiler hang
  //    implicit def toF[F[_], ErrorType](implicit monad: MonadError[F, Throwable], mapping: ResultMapping[RequestOperation[ErrorType, ?], EitherT[F, ErrorType, ?]]): ResultMapping[RequestOperation[ErrorType, ?], F] = mapping.mapK(flattenEitherT[F, ErrorType])
  //TODO unify methods
  private def flattenEitherTObservable[ErrorType]: EitherTObservable[Task, ErrorType, ?] ~> Observable = Lambda[EitherTObservable[Task, ErrorType, ?] ~> Observable](f => Observable.fromTask(f.value).flatMap {
    case Right(o) => o
    case Left(err) => Observable.raiseError(TransportException.RequestError(s"Error in request stream: $err"))
  })
  private def flattenEitherT[F[_], ErrorType](implicit monad: MonadError[F, Throwable]): EitherT[F, ErrorType, ?] ~> F = Lambda[EitherT[F, ErrorType, ?] ~> F](f => monad.flatMap(f.value) {
    case Right(v) => monad.pure(v)
    case Left(err) => monad.raiseError(TransportException.RequestError(s"Error in request: $err"))
  })
}

object RequestClient {
  def apply[PickleType, ErrorType](transport: RequestTransport[PickleType, RequestOperation[ErrorType, ?]]): Client[PickleType, RequestOperation[ErrorType, ?]] = Client[PickleType, RequestOperation[ErrorType, ?]](transport) //TODO, DefaultLogHandler[RequestOperation[ErrorType, ?], Throwable])
  def apply[PickleType, ErrorType](transport: RequestTransport[PickleType, RequestOperation[ErrorType, ?]], logger: LogHandler[RequestOperation[ErrorType, ?]]): Client[PickleType, RequestOperation[ErrorType, ?]] = Client[PickleType, RequestOperation[ErrorType, ?]](transport, logger)
}
