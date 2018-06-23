package covenant

import cats.data.{EitherT, Nested}
import cats.{Functor, Monad, MonadError, ~>}
import cats.syntax.monadError._
import cats.implicits._
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import sloth._

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}

case class RequestOperation[+ErrorType, +T](single: Task[Either[ErrorType, T]], stream: Observable[Either[ErrorType, T]])
object RequestOperation {
  def apply[T](value: T): RequestOperation[Nothing, T] = RequestOperation(
    Task.pure(Right(value)),
    Observable.pure(Right(value)))
  def raiseError[ErrorType](error: ErrorType): RequestOperation[ErrorType, Nothing] = RequestOperation[ErrorType, Nothing](
    Task.pure(Left(error)),
    Observable.pure(Left(error)))

  implicit def monadError[ErrorType]: MonadError[RequestOperation[ErrorType, ?], ErrorType] = new MonadError[RequestOperation[ErrorType, ?], ErrorType] {
    def pure[A](x: A): RequestOperation[ErrorType,A] = RequestOperation(x)
    def handleErrorWith[A](fa: RequestOperation[ErrorType,A])(f: ErrorType => RequestOperation[ErrorType,A]): RequestOperation[ErrorType,A] = RequestOperation(
      fa.single.flatMap {
        case Right(v) => Task.pure(Right(v))
        case Left(err) => f(err).single
      },
      fa.stream.flatMap {
        case Right(v) => Observable.pure(Right(v))
        case Left(err) => f(err).stream
      }
    )
    def raiseError[A](e: ErrorType): RequestOperation[ErrorType,A] = RequestOperation.raiseError(e)
    def flatMap[A, B](fa: RequestOperation[ErrorType,A])(f: A => RequestOperation[ErrorType,B]): RequestOperation[ErrorType,B] = RequestOperation(
      fa.single.flatMap {
        case Right(v) => f(v).single
        case Left(err) => Task.pure(Left(err))
      },
      fa.stream.flatMap {
        case Right(v) => f(v).stream
        case Left(err) => Observable.pure(Left(err))
      }
    )
    def tailRecM[A, B](a: A)(f: A => RequestOperation[ErrorType,Either[A,B]]): RequestOperation[ErrorType,B] = {
      val res = f(a)
      RequestOperation(
        res.single.flatMap {
          case Right(Left(a)) => tailRecM(a)(f).single
          case Right(Right(b)) => Task.pure(Right(b))
          case Left(err) => Task.pure(Left(err))
        },
        res.stream.flatMap {
          case Right(Left(a)) => tailRecM(a)(f).stream
          case Right(Right(b)) => Observable.pure(Right(b))
          case Left(err) => Observable.pure(Left(err))
        })
    }
  }

  implicit def toTaskEitherT[ErrorType]: ResultMapping[RequestOperation[ErrorType, ?], EitherT[Task, ErrorType, ?]] = ResultMapping(Lambda[RequestOperation[ErrorType, ?] ~> EitherT[Task, ErrorType, ?]](op => EitherT(op.single)))
  implicit def toFutureEitherT[ErrorType](implicit scheduler: Scheduler): ResultMapping[RequestOperation[ErrorType, ?], EitherT[Future, ErrorType, ?]] = ResultMapping(Lambda[RequestOperation[ErrorType, ?] ~> EitherT[Future, ErrorType, ?]](op => EitherT(op.single.runAsync)))
  implicit def toObservableEitherT[ErrorType]: ResultMapping[RequestOperation[ErrorType, ?], EitherT[Observable, ErrorType, ?]] = ResultMapping(Lambda[RequestOperation[ErrorType, ?] ~> EitherT[Observable, ErrorType, ?]](op => EitherT(op.stream)))

//  implicit def toTaskEitherT[ErrorType]: ResultMapping[RequestOperation[ErrorType, ?], EitherT[Task, ErrorType, ?]] = ResultMapping(Lambda[RequestOperation[ErrorType, ?] ~> EitherT[Task, ErrorType, ?]](op => EitherT(op.single)))
//  implicit def toFutureEitherT[ErrorType](implicit scheduler: Scheduler): ResultMapping[RequestOperation[ErrorType, ?], EitherT[Future, ErrorType, ?]] = ResultMapping(Lambda[RequestOperation[ErrorType, ?] ~> EitherT[Future, ErrorType, ?]](op => EitherT(op.single.runAsync)))
  implicit def toObservable[ErrorType]: ResultMapping[RequestOperation[ErrorType, ?], Observable] = toObservableEitherT.mapK(flattenEitherT[Observable, ErrorType])

  //TODO why not? makes compiler hang
  //  implicit def toWithEitherT[F[_], ErrorType](implicit monad: MonadError[F, Throwable], mapping: ResultMapping[RequestOperation[ErrorType, ?], EitherT[F, ErrorType, ?]]): ResultMapping[RequestOperation[ErrorType, ?], F] = mapping.mapK(Lambda[EitherT[F, ErrorType, ?] ~> F](f => monad.flatMap(f.value) {
  //    case Right(v) => monad.pure(v)
  //    case Left(err) => monad.raiseError(TransportException.RequestError(s"Error in request: $err"))
  //  }))
  private def flattenEitherT[F[_], ErrorType](implicit monad: MonadError[F, Throwable]): EitherT[F, ErrorType, ?] ~> F = Lambda[EitherT[F, ErrorType, ?] ~> F](f => monad.flatMap(f.value) {
    case Right(v) => monad.pure(v)
    case Left(err) => monad.raiseError(TransportException.RequestError(s"Error in request: $err"))
  })
}
