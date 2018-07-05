package covenant

import cats.{Functor, ~>}
import monix.eval.Task
import monix.reactive.Observable
import sloth._

import scala.concurrent.{ExecutionContext, Future}

sealed trait RequestResponse[+State, +ErrorType, +T] {
  def map[R](f: T => R): RequestResponse[State, ErrorType, R]
  def mapError[E](f: ErrorType => E): RequestResponse[State, E, T]
}
object RequestResponse extends RequestType {
  sealed trait Value[State, +ErrorType, +T] extends RequestResponse[State, ErrorType, T] {
    def map[R](f: T => R): Value[State, ErrorType, R]
    def mapError[E](f: ErrorType => E): Value[State, E, T]
  }
  sealed trait PureValue[+ErrorType, +T] extends Value[Nothing, ErrorType, T] {
    def map[R](f: T => R): PureValue[ErrorType, R]
    def mapError[E](f: ErrorType => E): PureValue[E, T]
  }
  case class Single[ErrorType, T](task: SingleF[Task, ErrorType, T]) extends PureValue[ErrorType, T] {
    def map[R](f: T => R): Single[ErrorType, R] = Single(task.map(_.map(f)))
    def mapError[E](f: ErrorType => E): Single[E, T] = Single(task.map(_.left.map(f)))
  }
  case class Stream[ErrorType, T](task: StreamF[Task, Observable, ErrorType, T]) extends PureValue[ErrorType, T] {
    def map[R](f: T => R): Stream[ErrorType, R] = Stream(task.map(_.map(_.map(f))))
    def mapError[E](f: ErrorType => E): Stream[E, T] = Stream(task.map(_.left.map(f)))
  }
  case class StateWithValue[State, ErrorType, T](state: State, value: PureValue[ErrorType, T]) extends Value[State, ErrorType, T] {
    def map[R](f: T => R): StateWithValue[State, ErrorType, R] = copy(value = value.map(f))
    def mapError[E](f: ErrorType => E): StateWithValue[State, E, T] = copy(value = value.mapError(f))
  }
  case class StateFunction[State, ErrorType, T](function: State => Value[State, ErrorType, T]) extends RequestResponse[State, ErrorType, T] {
    def map[R](f: T => R): StateFunction[State, ErrorType, R] = StateFunction(function andThen (_.map(f)))
    def mapError[E](f: ErrorType => E): StateFunction[State, E, T] = StateFunction(function andThen (_.mapError(f)))
  }

  implicit def functor[State, ErrorType]: Functor[RequestResponse[State, ErrorType, ?]] = new Functor[RequestResponse[State, ErrorType, ?]] {
    def map[A,B](fa: RequestResponse[State, ErrorType, A])(f: A => B): RequestResponse[State, ErrorType, B] = fa.map(f)
  }

  implicit def fromTaskEither[ErrorType, State]: ResultMapping[SingleF[Task, ErrorType, ?], RequestResponse[State, ErrorType, ?]] = ResultMapping[SingleF[Task, ErrorType, ?], RequestResponse[State, ErrorType, ?]](Lambda[SingleF[Task, ErrorType, ?] ~> RequestResponse[State, ErrorType, ?]](Single(_)))
  implicit def fromFutureEither[ErrorType, State]: ResultMapping[SingleF[Future, ErrorType, ?], RequestResponse[State, ErrorType, ?]] = ResultMapping[SingleF[Future, ErrorType, ?], RequestResponse[State, ErrorType, ?]](Lambda[SingleF[Future, ErrorType, ?] ~> RequestResponse[State, ErrorType, ?]](f => Single(Task.fromFuture(f))))
  implicit def fromTaskEitherObservable[ErrorType, State]: ResultMapping[StreamF[Task, Observable, ErrorType, ?], RequestResponse[State, ErrorType, ?]] = ResultMapping[StreamF[Task, Observable, ErrorType, ?], RequestResponse[State, ErrorType, ?]](Lambda[StreamF[Task, Observable, ErrorType, ?] ~> RequestResponse[State, ErrorType, ?]](Stream(_)))
  implicit def formFutureEitherObservable[ErrorType, State]: ResultMapping[StreamF[Future, Observable, ErrorType, ?], RequestResponse[State, ErrorType, ?]] = ResultMapping[StreamF[Future, Observable, ErrorType, ?], RequestResponse[State, ErrorType, ?]](Lambda[StreamF[Future, Observable, ErrorType, ?] ~> RequestResponse[State, ErrorType, ?]](f => Stream(Task.fromFuture(f))))

  implicit def fromTask[ErrorType, State]: ResultMapping[Task, RequestResponse[State, ErrorType, ?]] = fromTaskEither.contramapK(Lambda[Task ~> SingleF[Task, ErrorType, ?]](_.map(Right.apply)))
  implicit def fromFuture[ErrorType, State](implicit ec: ExecutionContext): ResultMapping[Future, RequestResponse[State, ErrorType, ?]] = fromFutureEither.contramapK(Lambda[Future ~> SingleF[Future, ErrorType, ?]](_.map(Right.apply)))
  implicit def fromObservable[ErrorType, State]: ResultMapping[Observable, RequestResponse[State, ErrorType, ?]] = fromTaskEitherObservable.contramapK(Lambda[Observable ~> StreamF[Task, Observable, ErrorType, ?]](o => Task.pure(Right(o))))

  implicit def fromStateFunction[ErrorType, State, F[_]](implicit mapping: ResultMapping[F, RequestResponse[State, ErrorType, ?]]): ResultMapping[Lambda[T => State => F[T]], RequestResponse[State, ErrorType, ?]] = ResultMapping[Lambda[T => State => F[T]], RequestResponse[State, ErrorType, ?]](Lambda[Lambda[T => State => F[T]] ~> RequestResponse[State, ErrorType, ?]](_ => ???))
}

object RequestRouter {
  def apply[PickleType, ErrorType]: Router[PickleType, RequestResponse[Unit, ErrorType, ?]] = Router[PickleType, RequestResponse[Unit, ErrorType, ?]]
  def withState[PickleType, ErrorType, State]: Router[PickleType, RequestResponse[State, ErrorType, ?]] = Router[PickleType, RequestResponse[State, ErrorType, ?]]
}
