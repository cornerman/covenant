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
object RequestResponse {
  sealed trait Result[+State, +ErrorType, +T] extends RequestResponse[State, ErrorType, T] {
    def value: RequestReturnValue[ErrorType, T]
    def map[R](f: T => R): Result[State, ErrorType, R]
    def mapError[E](f: ErrorType => E): Result[State, E, T]
  }
  case class PureValue[ErrorType, T](value: RequestReturnValue[ErrorType, T]) extends Result[Nothing, ErrorType, T] {
    def map[R](f: T => R): PureValue[ErrorType, R] = copy(value = value.map(f))
    def mapError[E](f: ErrorType => E): PureValue[E, T] = copy(value = value.mapError(f))
  }
  case class StateWithValue[State, ErrorType, T](state: Task[State], value: RequestReturnValue[ErrorType, T]) extends Result[State, ErrorType, T] {
    def map[R](f: T => R): StateWithValue[State, ErrorType, R] = copy(value = value.map(f))
    def mapError[E](f: ErrorType => E): StateWithValue[State, E, T] = copy(value = value.mapError(f))
  }
  case class StateFunction[State, ErrorType, T](function: State => Result[State, ErrorType, T]) extends RequestResponse[State, ErrorType, T] {
    def map[R](f: T => R): StateFunction[State, ErrorType, R] = StateFunction(function andThen (_.map(f)))
    def mapError[E](f: ErrorType => E): StateFunction[State, E, T] = StateFunction(function andThen (_.mapError(f)))
  }

  implicit def functor[State, ErrorType]: Functor[RequestResponse[State, ErrorType, ?]] = new Functor[RequestResponse[State, ErrorType, ?]] {
    def map[A,B](fa: RequestResponse[State, ErrorType, A])(f: A => B): RequestResponse[State, ErrorType, B] = fa.map(f)
  }

  implicit def fromPureValue[ErrorType, State, F[_]](implicit mapping: ResultMapping[F, RequestReturnValue[ErrorType, ?]]): ResultMapping[F, RequestResponse[State, ErrorType, ?]] = ResultMapping[F, RequestResponse[State, ErrorType, ?]](Lambda[F ~> RequestResponse[State, ErrorType, ?]](f => PureValue(mapping(f))))
  implicit def fromStateFunctionF[ErrorType, State, F[_]](implicit mapping: ResultMapping[F, RequestReturnValue[ErrorType, ?]]): ResultMapping[Lambda[T => State => F[T]], RequestResponse[State, ErrorType, ?]] = new ResultMapping[Lambda[T => State => F[T]], RequestResponse[State, ErrorType, ?]] {
    def apply[T](f: State => F[T]): RequestResponse[State, ErrorType, T] = StateFunction[State, ErrorType, T](s => PureValue(mapping(f(s))))
  }
}
sealed trait RequestReturnValue[+ErrorType, +T] {
  def map[R](f: T => R): RequestReturnValue[ErrorType, R]
  def mapError[E](f: ErrorType => E): RequestReturnValue[E, T]
}
object RequestReturnValue {
  import RequestType._

  case class Single[ErrorType, T](task: SingleF[Task, ErrorType, T]) extends RequestReturnValue[ErrorType, T] {
    def map[R](f: T => R): Single[ErrorType, R] = Single(task.map(_.map(f)))
    def mapError[E](f: ErrorType => E): Single[E, T] = Single(task.map(_.left.map(f)))
  }

  case class Stream[ErrorType, T](task: StreamF[Task, Observable, ErrorType, T]) extends RequestReturnValue[ErrorType, T] {
    def map[R](f: T => R): Stream[ErrorType, R] = Stream(task.map(_.map(_.map(f))))
    def mapError[E](f: ErrorType => E): Stream[E, T] = Stream(task.map(_.left.map(f)))
  }

  implicit def functor[ErrorType]: Functor[RequestReturnValue[ErrorType, ?]] = new Functor[RequestReturnValue[ErrorType, ?]] {
    def map[A, B](fa: RequestReturnValue[ErrorType, A])(f: A => B): RequestReturnValue[ErrorType, B] = fa.map(f)
  }

  implicit def fromTaskEither[ErrorType]: ResultMapping[SingleF[Task, ErrorType, ?], RequestReturnValue[ErrorType, ?]] = ResultMapping[SingleF[Task, ErrorType, ?], RequestReturnValue[ErrorType, ?]](Lambda[SingleF[Task, ErrorType, ?] ~> RequestReturnValue[ErrorType, ?]](Single(_)))
  implicit def fromFutureEither[ErrorType]: ResultMapping[SingleF[Future, ErrorType, ?], RequestReturnValue[ErrorType, ?]] = ResultMapping[SingleF[Future, ErrorType, ?], RequestReturnValue[ErrorType, ?]](Lambda[SingleF[Future, ErrorType, ?] ~> RequestReturnValue[ErrorType, ?]](f => Single(Task.fromFuture(f))))
  implicit def fromTaskEitherObservable[ErrorType]: ResultMapping[StreamF[Task, Observable, ErrorType, ?], RequestReturnValue[ErrorType, ?]] = ResultMapping[StreamF[Task, Observable, ErrorType, ?], RequestReturnValue[ErrorType, ?]](Lambda[StreamF[Task, Observable, ErrorType, ?] ~> RequestReturnValue[ErrorType, ?]](Stream(_)))
  implicit def fromFutureEitherObservable[ErrorType]: ResultMapping[StreamF[Future, Observable, ErrorType, ?], RequestReturnValue[ErrorType, ?]] = ResultMapping[StreamF[Future, Observable, ErrorType, ?], RequestReturnValue[ErrorType, ?]](Lambda[StreamF[Future, Observable, ErrorType, ?] ~> RequestReturnValue[ErrorType, ?]](f => Stream(Task.fromFuture(f))))

  implicit def fromTask[ErrorType]: ResultMapping[Task, RequestReturnValue[ErrorType, ?]] = fromTaskEither.contramapK(Lambda[Task ~> SingleF[Task, ErrorType, ?]](_.map(Right.apply)))
  implicit def fromFuture[ErrorType](implicit ec: ExecutionContext): ResultMapping[Future, RequestReturnValue[ErrorType, ?]] = fromFutureEither.contramapK(Lambda[Future ~> SingleF[Future, ErrorType, ?]](_.map(Right.apply)))
  implicit def fromObservable[ErrorType]: ResultMapping[Observable,RequestReturnValue[ErrorType, ?]] = fromTaskEitherObservable.contramapK(Lambda[Observable ~> StreamF[Task, Observable, ErrorType, ?]](o => Task.pure(Right(o))))
}

object RequestRouter {
  def apply[PickleType, ErrorType]: Router[PickleType, RequestResponse[Unit, ErrorType, ?]] = Router[PickleType, RequestResponse[Unit, ErrorType, ?]]
  def withState[PickleType, ErrorType, State]: Router[PickleType, RequestResponse[State, ErrorType, ?]] = Router[PickleType, RequestResponse[State, ErrorType, ?]]
}
