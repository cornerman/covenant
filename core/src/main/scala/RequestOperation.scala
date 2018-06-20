package covenant

import cats.data.{EitherT, Nested}
import cats.{MonadError, ~>}
import javax.lang.model.`type`.ErrorType
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import sloth.{RequestTransport, ResultMapping}

import scala.concurrent.Future

trait RequestOperation[+ErrorType, +T] {
  def single: Task[Either[ErrorType, T]]
  def stream: Task[Either[ErrorType, Observable[T]]]
}
object RequestOperation {
  type Single[T] = EitherT[Task, ErrorType, T]
  type Stream[T] = EitherT[Task, ErrorType, Observable[T]]
  def apply[ErrorType, T](singlef: => Task[Either[ErrorType, T]], streamf: => Task[Either[ErrorType, Observable[T]]]): RequestOperation[ErrorType, T] = new RequestOperation[ErrorType, T] {
    def single = singlef
    def stream = streamf
  }
  def apply[T](value: Task[T]): RequestOperation[Nothing, T] = new RequestOperation[Nothing, T] {
    def single = value.map(Right.apply)
    def stream = value.map(v => Right(Observable(v)))
  }
  def apply[T](value: T): RequestOperation[Nothing, T] = new RequestOperation[Nothing, T] {
    def single = Task(Right(value))
    def stream = Task(Right(Observable(value)))
  }
  def error[ErrorType](error: ErrorType): RequestOperation[ErrorType, Nothing] = new RequestOperation[ErrorType, Nothing] {
    def single = Task(Left(error))
    def stream = single
  }

//  implicit def monadError[ErrorType]: MonadError[RequestOperation[ErrorType, ?], ErrorType] = new MonadError[RequestOperation[ErrorType, ?], ErrorType] {
//    def pure[A](x: A): RequestOperation[ErrorType, A] = RequestOperation(x)
//    def handleErrorWith[A](fa: RequestOperation[ErrorType, A])(f: ErrorType => RequestOperation[ErrorType, A]): RequestOperation[ErrorType, A] = RequestOperation(
//      fa.single.onErrorHandleWith(err => f(err).single), fa.stream.onErrorHandleWith(err => f(err).stream)
//    )
//    def raiseError[A](e: ErrorType): RequestOperation[ErrorType, A] = RequestOperation.error(e)
//    def flatMap[A, B](fa: RequestOperation[ErrorType, A])(f: A => RequestOperation[ErrorType, B]): RequestOperation[ErrorType, B] = RequestOperation(
//      fa.single.flatMap(v => f(v).single), fa.stream.flatMap(v => f(v).stream)
//    )
//    def tailRecM[A, B](a: A)(f: A => RequestOperation[ErrorType, Either[A,B]]): RequestOperation[ErrorType, B] = {
//      val mSingle = implicitly[MonadError[EitherT[Task, ErrorType, ?]]]
//      val x = mSingle.tailRecM[A,B](a)(f andThen (op => EitherT[Task, ErrorType, Either[A,B]](op.single)))
////    RequestOperation(
//    //      Task.tailRecM(a)(f andThen (_.single)), Observable.tailRecM(a)(f andThen (_.stream))
////    )
//      ???
//    }
//  }
  implicit val monadErrorThrowable: MonadError[RequestOperation[ErrorType, ?], Throwable] = new MonadError[RequestOperation[ErrorType, ?], Throwable] {
    def pure[A](x: A): RequestOperation[ErrorType, A] = RequestOperation(x)
    def handleErrorWith[A](fa: RequestOperation[ErrorType, A])(f: ErrorType => RequestOperation[ErrorType, A]): RequestOperation[ErrorType, A] = RequestOperation(
      fa.single.onErrorHandleWith(err => f(err).single), fa.stream.onErrorHandleWith(err => f(err).stream)
    )
    def raiseError[A](e: Throwable): RequestOperation[ErrorType, A] = RequestOperation(Task.raiseError(e))
    def flatMap[A, B](fa: RequestOperation[ErrorType, A])(f: A => RequestOperation[ErrorType, B]): RequestOperation[ErrorType, B] = RequestOperation(
      fa.single.flatMap(v => f(v).single), fa.stream.flatMap(v => f(v).stream)
    )
    def tailRecM[A, B](a: A)(f: A => RequestOperation[ErrorType, Either[A,B]]): RequestOperation[ErrorType, B] = {
      val mSingle = implicitly[MonadError[EitherT[Task, ErrorType, ?]]]
      val x = mSingle.tailRecM[A,B](a)(f andThen (op => EitherT[Task, ErrorType, Either[A,B]](op.single)))
      //    RequestOperation(
      //      Task.tailRecM(a)(f andThen (_.single)), Observable.tailRecM(a)(f andThen (_.stream))
      //    )
      ???
    }
  }

//  implicit val toTask: ResultMapping[RequestOperation, Task] = ResultMapping(Lambda[RequestOperation ~> Task](_.single))
//  implicit def toFuture(implicit s: Scheduler): ResultMapping[RequestOperation, Future] = ResultMapping(Lambda[RequestOperation ~> Future](_.single.runAsync))
//  implicit val toObservable: ResultMapping[RequestOperation, Observable] = ResultMapping(Lambda[RequestOperation ~> Observable](_.stream))
//
//  implicit def toEitherT[Result[_], ErrorType](implicit mapping: ResultMapping[RequestOperation, Result]): ResultMapping[EitherT[RequestOperation, ErrorType, ?], EitherT[Result, ErrorType, ?]] = ResultMapping(Lambda[EitherT[RequestOperation, ErrorType, ?] ~> EitherT[Result, ErrorType, ?]](_.mapK(mapping)))
//
//  implicit class FlattenError[ErrorType, PickleType](val transport: RequestTransport[PickleType, EitherT[RequestOperation, ErrorType, ?]]) extends AnyVal {
//    def flattenError: RequestTransport[PickleType, RequestOperation] = flattenError(e => TransportException(e.toString))
//    def flattenError(toError: ErrorType => Throwable): RequestTransport[PickleType, RequestOperation] = transport.mapK(Lambda[EitherT[RequestOperation, ErrorType, ?] ~> RequestOperation](op => monadError.flatMap(op.value) {
//      case Right(v) => monadError.pure(v)
//      case Left(err) => monadError.raiseError(toError(err))
//    }))
//  }
}
