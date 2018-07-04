package covenant

import cats.{Functor, ~>}
import monix.eval.Task
import monix.reactive.Observable
import sloth._

import scala.concurrent.{ExecutionContext, Future}

sealed trait RequestResponse[ErrorType, T] {
  def map[R](f: T => R): RequestResponse[ErrorType, R]
  def mapError[E](f: ErrorType => E): RequestResponse[E, T]
}
object RequestResponse extends RequestType {
  case class Single[ErrorType, T](task: SingleF[Task, ErrorType, T]) extends RequestResponse[ErrorType, T] {
    def map[R](f: T => R): Single[ErrorType, R] = copy(task.map(_.map(f)))
    def mapError[E](f: ErrorType => E): Single[E, T] = copy(task.map(_.left.map(f)))
  }
  case class Stream[ErrorType, T](task: StreamF[Task, Observable, ErrorType, T]) extends RequestResponse[ErrorType, T] {
    def map[R](f: T => R): Stream[ErrorType, R] = copy(task.map(_.map(_.map(f))))
    def mapError[E](f: ErrorType => E): Stream[E, T] = copy(task.map(_.left.map(f)))
  }

  implicit def functor[ErrorType]: Functor[RequestResponse[ErrorType, ?]] = new Functor[RequestResponse[ErrorType, ?]] {
    def map[A,B](fa: RequestResponse[ErrorType, A])(f: A => B): RequestResponse[ErrorType, B] = fa.map(f)
  }

  implicit def fromTaskEither[ErrorType]: ResultMapping[SingleF[Task, ErrorType, ?], RequestResponse[ErrorType, ?]] = ResultMapping[SingleF[Task, ErrorType, ?], RequestResponse[ErrorType, ?]](Lambda[SingleF[Task, ErrorType, ?] ~> RequestResponse[ErrorType, ?]](Single(_)))
  implicit def fromFutureEither[ErrorType](implicit ec: ExecutionContext): ResultMapping[SingleF[Future, ErrorType, ?], RequestResponse[ErrorType, ?]] = ResultMapping[SingleF[Future, ErrorType, ?], RequestResponse[ErrorType, ?]](Lambda[SingleF[Future, ErrorType, ?] ~> RequestResponse[ErrorType, ?]](f => Single(Task.fromFuture(f))))
  implicit def fromTaskEitherObservable[ErrorType]: ResultMapping[StreamF[Task, Observable, ErrorType, ?], RequestResponse[ErrorType, ?]] = ResultMapping[StreamF[Task, Observable, ErrorType, ?], RequestResponse[ErrorType, ?]](Lambda[StreamF[Task, Observable, ErrorType, ?] ~> RequestResponse[ErrorType, ?]](Stream(_)))
  implicit def formFutureEitherObservable[ErrorType](implicit ec: ExecutionContext): ResultMapping[StreamF[Future, Observable, ErrorType, ?], RequestResponse[ErrorType, ?]] = ResultMapping[StreamF[Future, Observable, ErrorType, ?], RequestResponse[ErrorType, ?]](Lambda[StreamF[Future, Observable, ErrorType, ?] ~> RequestResponse[ErrorType, ?]](f => Stream(Task.fromFuture(f))))

  //TODO contramap?
  //TODO why not Nothing as ErrorType? is covariant
  implicit def fromTask[ErrorType]: ResultMapping[Task, RequestResponse[ErrorType, ?]] = ResultMapping(Lambda[Task ~> RequestResponse[ErrorType, ?]](t => Single(t.map(Right.apply))))
  implicit def fromFuture[ErrorType](implicit ec: ExecutionContext): ResultMapping[Future, RequestResponse[ErrorType, ?]] = ResultMapping(Lambda[Future ~> RequestResponse[ErrorType, ?]](f => Single(Task.fromFuture(f).map(Right.apply))))
  implicit def fromObservable[ErrorType]: ResultMapping[Observable, RequestResponse[ErrorType, ?]] = ResultMapping(Lambda[Observable ~> RequestResponse[ErrorType, ?]](o => Stream(Task.pure(Right(o)))))
}

object RequestRouter {
  def apply[PickleType, ErrorType]: Router[PickleType, RequestResponse[ErrorType, ?]] = Router[PickleType, RequestResponse[ErrorType, ?]]
}
