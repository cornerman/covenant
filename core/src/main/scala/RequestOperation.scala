package covenant

import cats.data.EitherT
import cats.implicits._
import cats.{MonadError, ~>}
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import sloth._

import scala.concurrent.Future

case class RequestOperation[+ErrorType, +T](single: Task[Either[ErrorType, T]], stream: Observable[Either[ErrorType, T]])
object RequestOperation {
  def apply[T](value: T): RequestOperation[Nothing, T] = RequestOperation(Task.pure(Right(value)), Observable.pure(Right(value)))
  def raiseError[ErrorType](error: ErrorType): RequestOperation[ErrorType, Nothing] = RequestOperation(Task.pure(Left(error)), Observable.pure(Left(error)))
  def raiseThrowable(ex: Throwable): RequestOperation[Nothing, Nothing] = RequestOperation(Task.raiseError(ex), Observable.raiseError(ex))

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

//  implicit def monadErrorThrowable[ErrorType]: MonadError[RequestOperation[ErrorType, ?], Throwable] = new MonadError[RequestOperation[ErrorType, ?], Throwable] {
//    def pure[A](x: A): RequestOperation[ErrorType,A] = RequestOperation(x)
//    def handleErrorWith[A](fa: RequestOperation[ErrorType,A])(f: Throwable => RequestOperation[ErrorType,A]): RequestOperation[ErrorType,A] = RequestOperation(
//      fa.single.handleErrorWith(f andThen (_.single)),
//      fa.stream.handleErrorWith(f andThen (_.stream))
//    )
//    def raiseError[A](e: Throwable): RequestOperation[ErrorType,A] = RequestOperation.raiseThrowable(e)
//    def flatMap[A, B](fa: RequestOperation[ErrorType,A])(f: A => RequestOperation[ErrorType,B]): RequestOperation[ErrorType,B] = RequestOperation(
//      fa.single.flatMap {
//        case Right(v) => f(v).single
//        case Left(err) => Task.pure(Left(err))
//      },
//      fa.stream.flatMap {
//        case Right(v) => f(v).stream
//        case Left(err) => Observable.pure(Left(err))
//      }
//    )
//    def tailRecM[A, B](a: A)(f: A => RequestOperation[ErrorType,Either[A,B]]): RequestOperation[ErrorType,B] = {
//      val res = f(a)
//      RequestOperation(
//        res.single.flatMap {
//          case Right(Left(a)) => tailRecM(a)(f).single
//          case Right(Right(b)) => Task.pure(Right(b))
//          case Left(err) => Task.pure(Left(err))
//        },
//        res.stream.flatMap {
//          case Right(Left(a)) => tailRecM(a)(f).stream
//          case Right(Right(b)) => Observable.pure(Right(b))
//          case Left(err) => Observable.pure(Left(err))
//        })
//    }
//  }

  implicit def toTaskEitherT[ErrorType]: ResultMapping[RequestOperation[ErrorType, ?], EitherT[Task, ErrorType, ?]] = ResultMapping(Lambda[RequestOperation[ErrorType, ?] ~> EitherT[Task, ErrorType, ?]](op => EitherT(op.single)))
  implicit def toFutureEitherT[ErrorType](implicit scheduler: Scheduler): ResultMapping[RequestOperation[ErrorType, ?], EitherT[Future, ErrorType, ?]] = ResultMapping(Lambda[RequestOperation[ErrorType, ?] ~> EitherT[Future, ErrorType, ?]](op => EitherT(op.single.runAsync)))
  implicit def toObservableEitherT[ErrorType]: ResultMapping[RequestOperation[ErrorType, ?], EitherT[Observable, ErrorType, ?]] = ResultMapping(Lambda[RequestOperation[ErrorType, ?] ~> EitherT[Observable, ErrorType, ?]](op => EitherT(op.stream)))

  implicit def toTask[ErrorType]: ResultMapping[RequestOperation[ErrorType, ?], Task] = toTaskEitherT.mapK(flattenEitherT[Task, ErrorType])
  implicit def toFuture[ErrorType](implicit scheduler: Scheduler): ResultMapping[RequestOperation[ErrorType, ?], Future] = toFutureEitherT.mapK(flattenEitherT[Future, ErrorType])
  implicit def toObservable[ErrorType]: ResultMapping[RequestOperation[ErrorType, ?], Observable] = toObservableEitherT.mapK(flattenEitherT[Observable, ErrorType])

  //TODO: does not resolve?
  //    implicit def toF[F[_], ErrorType](implicit monad: MonadError[F, Throwable]): ResultMapping[EitherT[F, ErrorType, ?], F] = ResultMapping(flattenEitherT[F, ErrorType])
  //    implicit def toFromTo[From[_], To[_], ErrorType](implicit from: ResultMapping[RequestOperation[ErrorType, ?], EitherT[From, ErrorType, ?]], to: ResultMapping[EitherT[From, ErrorType, ?], To]): ResultMapping[RequestOperation[ErrorType, ?], To] = from.mapK(to)
  //TODO makes compiler hang
//  implicit def toF[F[_], ErrorType](implicit monad: MonadError[F, Throwable], mapping: ResultMapping[RequestOperation[ErrorType, ?], EitherT[F, ErrorType, ?]]): ResultMapping[RequestOperation[ErrorType, ?], F] = mapping.mapK(flattenEitherT[F, ErrorType])
  private def flattenEitherT[F[_], ErrorType](implicit monad: MonadError[F, Throwable]): EitherT[F, ErrorType, ?] ~> F = Lambda[EitherT[F, ErrorType, ?] ~> F](f => monad.flatMap(f.value) {
    case Right(v) => monad.pure(v)
    case Left(err) => monad.raiseError(TransportException.RequestError(s"Error in request: $err"))
  })
}

object RequestClient {
  def apply[PickleType, ErrorType](transport: RequestTransport[PickleType, RequestOperation[ErrorType, ?]])(implicit monad: ClientResultError[RequestOperation[ErrorType, ?]]): Client[PickleType, RequestOperation[ErrorType, ?]] = Client[PickleType, RequestOperation[ErrorType, ?]](transport, DefaultLogHandler[RequestOperation[ErrorType, ?], ErrorType])
  def apply[PickleType, ErrorType](transport: RequestTransport[PickleType, RequestOperation[ErrorType, ?]], logger: LogHandler[RequestOperation[ErrorType, ?]])(implicit monad: ClientResultError[RequestOperation[ErrorType, ?]]): Client[PickleType, RequestOperation[ErrorType, ?]] = Client[PickleType, RequestOperation[ErrorType, ?]](transport, logger)
}
