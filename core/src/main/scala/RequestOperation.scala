package covenant

import cats.data.EitherT
import cats.{MonadError, ~>}
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import sloth.{RequestTransport, ResultMapping}

import scala.concurrent.Future

trait RequestOperation[T] {
  def single: Task[T]
  def stream: Observable[T]
}
object RequestOperation {
  def apply[T](singlef: => Task[T], streamf: => Observable[T]): RequestOperation[T] = new RequestOperation[T] {
    def single: Task[T] = singlef
    def stream: Observable[T] = streamf
  }

  implicit val monadError: MonadError[RequestOperation, Throwable] = new MonadError[RequestOperation, Throwable] {
    def pure[A](x: A): RequestOperation[A] = RequestOperation(Task(x), Observable(x))
    def handleErrorWith[A](fa: RequestOperation[A])(f: Throwable => RequestOperation[A]): RequestOperation[A] = RequestOperation(
      fa.single.onErrorHandleWith(err => f(err).single), fa.stream.onErrorHandleWith(err => f(err).stream)
    )
    def raiseError[A](e: Throwable): RequestOperation[A] = RequestOperation(Task.raiseError(e), Observable.raiseError(e))
    def flatMap[A, B](fa: RequestOperation[A])(f: A => RequestOperation[B]): RequestOperation[B] = RequestOperation(
      fa.single.flatMap(v => f(v).single), fa.stream.flatMap(v => f(v).stream)
    )
    def tailRecM[A, B](a: A)(f: A => RequestOperation[Either[A,B]]): RequestOperation[B] = RequestOperation(
      Task.tailRecM(a)(f andThen (_.single)), Observable.tailRecM(a)(f andThen (_.stream))
    )
  }

  implicit val toTask: ResultMapping[RequestOperation, Task] = ResultMapping(Lambda[RequestOperation ~> Task](_.single))
  implicit def toFuture(implicit s: Scheduler): ResultMapping[RequestOperation, Future] = ResultMapping(Lambda[RequestOperation ~> Future](_.single.runAsync))
  implicit val toObservable: ResultMapping[RequestOperation, Observable] = ResultMapping(Lambda[RequestOperation ~> Observable](_.stream))

  implicit def toEitherT[Result[_], ErrorType](implicit mapping: ResultMapping[RequestOperation, Result]): ResultMapping[EitherT[RequestOperation, ErrorType, ?], EitherT[Result, ErrorType, ?]] = ResultMapping(Lambda[EitherT[RequestOperation, ErrorType, ?] ~> EitherT[Result, ErrorType, ?]](_.mapK(mapping)))

  implicit class FlattenError[ErrorType, PickleType](val transport: RequestTransport[PickleType, EitherT[RequestOperation, ErrorType, ?]]) extends AnyVal {
    def flattenError: RequestTransport[PickleType, RequestOperation] = flattenError(e => TransportException(e.toString))
    def flattenError(toError: ErrorType => Throwable): RequestTransport[PickleType, RequestOperation] = transport.mapK(Lambda[EitherT[RequestOperation, ErrorType, ?] ~> RequestOperation](op => monadError.flatMap(op.value) {
      case Right(v) => monadError.pure(v)
      case Left(err) => monadError.raiseError(toError(err))
    }))
  }
}
