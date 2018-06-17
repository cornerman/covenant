package covenant

import cats.data.EitherT
import cats.{MonadError, ~>}
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import sloth.{RequestTransport, ResultMapping}

import scala.concurrent.Future

sealed trait RequestKind
object RequestKind {
  case object Single extends RequestKind
  case object Stream extends RequestKind
}

trait RequestOperation[T] {
  def apply(kind: RequestKind): Observable[T]
}
object RequestOperation {
  def apply[T](f: RequestKind => Observable[T]): RequestOperation[T] = new RequestOperation[T] {
    def apply(kind: RequestKind) = f(kind)
  }

  implicit val monadError: MonadError[RequestOperation, Throwable] = new MonadError[RequestOperation, Throwable] {
    def pure[A](x: A): RequestOperation[A] = RequestOperation(_ => Observable(x))
    def handleErrorWith[A](fa: RequestOperation[A])(f: Throwable => RequestOperation[A]): RequestOperation[A] = RequestOperation { kind =>
      fa(kind).onErrorHandleWith(err => f(err)(kind))
    }
    def raiseError[A](e: Throwable): RequestOperation[A] = RequestOperation(_ => Observable.raiseError(e))
    def flatMap[A, B](fa: RequestOperation[A])(f: A => RequestOperation[B]): RequestOperation[B] = RequestOperation { kind =>
      fa(kind).flatMap(v => f(v)(kind))
    }
    def tailRecM[A, B](a: A)(f: A => RequestOperation[Either[A,B]]): RequestOperation[B] = RequestOperation { kind =>
      Observable.tailRecM(a)(f andThen (_(kind)))
    }
  }

  implicit val toTask: ResultMapping[RequestOperation, Task] = ResultMapping(Lambda[RequestOperation ~> Task](_(RequestKind.Single).lastL))
  implicit def toFuture(implicit s: Scheduler): ResultMapping[RequestOperation, Future] = ResultMapping(Lambda[RequestOperation ~> Future](_(RequestKind.Single).lastL.runAsync))
  implicit val toObservable: ResultMapping[RequestOperation, Observable] = ResultMapping(Lambda[RequestOperation ~> Observable](_(RequestKind.Stream)))

  implicit def toEitherT[Result[_], ErrorType](implicit mapping: ResultMapping[RequestOperation, Result]): ResultMapping[EitherT[RequestOperation, ErrorType, ?], EitherT[Result, ErrorType, ?]] = ResultMapping(Lambda[EitherT[RequestOperation, ErrorType, ?] ~> EitherT[Result, ErrorType, ?]](_.mapK(mapping)))

  implicit class FlattenError[ErrorType, PickleType](val transport: RequestTransport[PickleType, EitherT[RequestOperation, ErrorType, ?]]) extends AnyVal {
    def flattenError: RequestTransport[PickleType, RequestOperation] = flattenError(e => TransportException(e.toString))
    def flattenError(toError: ErrorType => Throwable): RequestTransport[PickleType, RequestOperation] = transport.mapK(Lambda[EitherT[RequestOperation, ErrorType, ?] ~> RequestOperation](op => monadError.flatMap(op.value) {
      case Right(v) => monadError.pure(v)
      case Left(err) => monadError.raiseError(toError(err))
    }))
  }
}
