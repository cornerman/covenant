package covenant

import cats.implicits._
import cats.{MonadError, ~>}
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import sloth._

import scala.concurrent.Future

case class RequestOperation[+ErrorType, +T](single: RequestOperation.SingleF[Task, ErrorType, T], stream: RequestOperation.StreamF[Task, Observable, ErrorType, T]) {
  def map[R](f: T => R): RequestOperation[ErrorType, R] = RequestOperation(single.map(_.map(f)), stream.map(_.map(_.map(f))))
  def mapError[E](f: ErrorType => E): RequestOperation[E, T] = RequestOperation(single.map(_.left.map(f)), stream.map(_.left.map(f)))
}
object RequestOperation extends RequestType {

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

  implicit def toTaskEither[ErrorType]: ResultMapping[RequestOperation[ErrorType, ?], SingleF[Task, ErrorType, ?]] = ResultMapping[RequestOperation[ErrorType, ?], SingleF[Task, ErrorType, ?]](Lambda[RequestOperation[ErrorType, ?] ~> SingleF[Task, ErrorType, ?]](_.single))
  implicit def toFutureEither[ErrorType](implicit scheduler: Scheduler): ResultMapping[RequestOperation[ErrorType, ?], SingleF[Future, ErrorType, ?]] = ResultMapping[RequestOperation[ErrorType, ?], SingleF[Future, ErrorType, ?]](Lambda[RequestOperation[ErrorType, ?] ~> SingleF[Future, ErrorType, ?]](_.single.runAsync))
  implicit def toTaskEitherObservable[ErrorType]: ResultMapping[RequestOperation[ErrorType, ?], StreamF[Task, Observable, ErrorType, ?]] = ResultMapping[RequestOperation[ErrorType, ?], StreamF[Task, Observable, ErrorType, ?]](Lambda[RequestOperation[ErrorType, ?] ~> StreamF[Task, Observable, ErrorType, ?]](_.stream))
  implicit def toFutureEitherObservable[ErrorType](implicit scheduler: Scheduler): ResultMapping[RequestOperation[ErrorType, ?], StreamF[Future, Observable, ErrorType, ?]] = ResultMapping[RequestOperation[ErrorType, ?], StreamF[Future, Observable, ErrorType, ?]](Lambda[RequestOperation[ErrorType, ?] ~> StreamF[Future, Observable, ErrorType, ?]](_.stream.runAsync))

  implicit def toTask[ErrorType]: ResultMapping[RequestOperation[ErrorType, ?], Task] = toTaskEither.mapK(flattenSingleEither[Task, ErrorType])
  implicit def toFuture[ErrorType](implicit scheduler: Scheduler): ResultMapping[RequestOperation[ErrorType, ?], Future] = toFutureEither.mapK(flattenSingleEither[Future, ErrorType])
  implicit def toObservable[ErrorType]: ResultMapping[RequestOperation[ErrorType, ?], Observable] = toTaskEitherObservable.mapK(flattenStreamEither[ErrorType])

  //TODO: does not resolve?
  //    implicit def toF[F[_], ErrorType](implicit monad: MonadError[F, Throwable]): ResultMapping[EitherT[F, ErrorType, ?], F] = ResultMapping(flattenEitherT[F, ErrorType])
  //    implicit def toFromTo[From[_], To[_], ErrorType](implicit from: ResultMapping[RequestOperation[ErrorType, ?], EitherT[From, ErrorType, ?]], to: ResultMapping[EitherT[From, ErrorType, ?], To]): ResultMapping[RequestOperation[ErrorType, ?], To] = from.mapK(to)
  //TODO makes compiler hang
  //    implicit def toF[F[_], ErrorType](implicit monad: MonadError[F, Throwable], mapping: ResultMapping[RequestOperation[ErrorType, ?], EitherT[F, ErrorType, ?]]): ResultMapping[RequestOperation[ErrorType, ?], F] = mapping.mapK(flattenEitherT[F, ErrorType])
  //TODO unify methods
  private def flattenSingleEither[F[_], ErrorType](implicit monad: MonadError[F, Throwable]): SingleF[F, ErrorType, ?] ~> F = Lambda[SingleF[F, ErrorType, ?] ~> F](f => monad.flatMap(f) {
    case Right(v) => monad.pure(v)
    case Left(err) => monad.raiseError(TransportException.RequestError(s"Error in request: $err"))
  })
  private def flattenStreamEither[ErrorType]: StreamF[Task, Observable, ErrorType, ?] ~> Observable = Lambda[StreamF[Task, Observable, ErrorType, ?] ~> Observable](f => Observable.fromTask(f).flatMap {
    case Right(o) => o
    case Left(err) => Observable.raiseError(TransportException.RequestError(s"Error in request stream: $err"))
  })
}

object RequestClient {
  def apply[PickleType, ErrorType](transport: RequestTransport[PickleType, RequestOperation[ErrorType, ?]]): Client[PickleType, RequestOperation[ErrorType, ?]] = Client[PickleType, RequestOperation[ErrorType, ?]](transport) //TODO, DefaultLogHandler[RequestOperation[ErrorType, ?], Throwable])
  def apply[PickleType, ErrorType](transport: RequestTransport[PickleType, RequestOperation[ErrorType, ?]], logger: LogHandler[RequestOperation[ErrorType, ?]]): Client[PickleType, RequestOperation[ErrorType, ?]] = Client[PickleType, RequestOperation[ErrorType, ?]](transport, logger)
}
