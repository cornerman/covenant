package covenant

import cats.data.{EitherT, Nested}
import cats.{Functor, Monad, MonadError, ~>}
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import sloth._

import scala.concurrent.{ExecutionContext, Future}

case class RequestOperation[+ErrorType, +T](single: Task[Either[ErrorType, T]], stream: Observable[Either[ErrorType, T]])
object RequestOperation {
  def apply[T](value: Task[T]): RequestOperation[Nothing, T] = RequestOperation(
    value.map(Right.apply),
    value.map(v => Right(Observable(v))))
  def apply[T](value: T): RequestOperation[Nothing, T] = RequestOperation(
    Task.pure(Right(value)),
    Task.pure(Right(Observable(value))))
  def error[ErrorType](error: ErrorType): RequestOperation[ErrorType, Nothing] = RequestOperation[ErrorType, Nothing](
    Task.pure(Left(error)),
    Task.pure(Left(error)))

  implicit def clientResultError[ErrorType]: ClientResultErrorT[RequestOperation[ErrorType, ?], ErrorType] = new ClientResultErrorT[RequestOperation[ErrorType, ?], ErrorType] {
    def mapMaybe[T, R](result: RequestOperation[ErrorType, T])(f: T => Either[ErrorType, R]): RequestOperation[ErrorType, R] = RequestOperation(
      result.single.map(_.flatMap(f)), result.stream.map(_.map(_.flatMap(f andThen {
        case Right(v) => Observable(v)
        case Left(err) => Observable.raiseError(TransportException(s"Cannot stream response: $err"))
      }))))
    def raiseError[T](error: ErrorType): RequestOperation[ErrorType, T] = RequestOperation.error[ErrorType](error)
  }

  implicit def toTask[ErrorType]: ResultMapping[RequestOperation[ErrorType, ?], Task] = ResultMapping(Lambda[RequestOperation[ErrorType, ?] ~> Task](_.single.flatMap {
    case Right(v) => Task.pure(v)
    case Left(err) => Task.raiseError(TransportException(s"Response task has an error: $err"))
  }))
  implicit def toFuture[ErrorType](implicit scheduler: Scheduler): ResultMapping[RequestOperation[ErrorType, ?], Future] = toTask.mapK(Lambda[Task ~> Future](_.runAsync))
  implicit def toObservable[ErrorType]: ResultMapping[RequestOperation[ErrorType, ?], Observable] = ResultMapping(Lambda[RequestOperation[ErrorType, ?] ~> Observable](v => Observable.fromTask(v.stream).flatMap {
    case Right(v) => v
    case Left(err) => Observable.raiseError(TransportException(s"Response stream has an error: $err"))
  }))

  implicit def toTaskEither[ErrorType]: ResultMapping[RequestOperation[ErrorType, ?], EitherT[Task, ErrorType, ?]] = ResultMapping(Lambda[RequestOperation[ErrorType, ?] ~> EitherT[Task, ErrorType, ?]](_.single))
  implicit def toFutureEither[ErrorType](implicit scheduler: Scheduler): ResultMapping[RequestOperation[ErrorType, ?], Future] = toTaskEither.mapK(Lambda[Task ~> EitherT[Future, ErrorType, ?]](_.runAsync))
  implicit def toObservableEither[ErrorType]: ResultMapping[RequestOperation[ErrorType, ?], Lambda[T => EitherT[Task, ErrorType, Observable[T]]] = ResultMapping(Lambda[RequestOperation[ErrorType, ?] ~> Lambda[T => EitherT[Task, ErrorType, Observable[T]]](_.stream)
  implicit def toObservableEither[ErrorType]: ResultMapping[RequestOperation[ErrorType, ?], Lambda[T => EitherT[Future, ErrorType, Observable[T]]] = toObservableEither.mapK()

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
  // implicit def monadClientFailure[ErrorType]: MonadError[RequestOperation[ErrorType, ?], Throwable] = new MonadError[RequestOperation[ErrorType, ?], Throwable] {
  //   def pure[A](x: A): RequestOperation[ErrorType, A] = RequestOperation(x)
  //   def handleErrorWith[A](fa: RequestOperation[ErrorType, A])(f: Throwable => RequestOperation[ErrorType, A]): RequestOperation[ErrorType, A] =
  //     RequestOperation(fa.single.onErrorHandleWith(err => f(err).single), fa.stream.onErrorHandleWith(err => f(err).stream))
  //   def raiseError[A](e: Throwable): RequestOperation[ErrorType, A] = RequestOperation(Task.raiseError(e))
  //   def flatMap[A, B](fa: RequestOperation[ErrorType, A])(f: A => RequestOperation[ErrorType, B]): RequestOperation[ErrorType, B] = {
  //     val single: Task[Either[ErrorType, B]] = fa.single.flatMap {
  //       case Right(v) => f(v).single
  //       case Left(err) => Task.pure(Left(err))
  //     }
  //     val stream: Task[Either[ErrorType, Observable[B]]] = fa.stream.map {
  //       case Right(v) => Task.pure(Right(v.map{ v: A => 
  //           val x: Task[Either[ErrorType, Observable[B]]] = f(v).stream
  //           ???
  //       }))
  //       case Left(err) => Task.pure(Left(err))
  //     }
  //     RequestOperation(single, stream)
  //   }
  //   def tailRecM[A, B](a: A)(f: A => RequestOperation[ErrorType, Either[A,B]]): RequestOperation[ErrorType, B] = {
  //     // val res = f(a)
  //     // val task = flatMap(res) {
  //     //   case res @ Right(_) => res
  //     //   case Left(a) => f(a)
  //     // }
  //     // RequestOperation(
  //     // val mSingle = implicitly[MonadError[EitherT[Task, ErrorType, ?]]]
  //     // val x = mSingle.tailRecM[A,B](a)(f andThen (op => EitherT[Task, ErrorType, Either[A,B]](op.single)))
  //     //    RequestOperation(
  //     //      Task.tailRecM(a)(f andThen (_.single)), Observable.tailRecM(a)(f andThen (_.stream))
  //     //    )
  //     ???
  //   }
  // }


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
