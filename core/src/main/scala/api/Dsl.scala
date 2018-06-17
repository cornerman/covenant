package covenant.api

import cats.Functor
import monix.execution.Scheduler
import monix.reactive.Observable

import scala.concurrent.Future

object ClientDsl {
  type Result[R[_], T] = R[T]
}

object RawServerDsl {
  sealed trait ApiResult[+Event, +State, R[_], T]
  object ApiResult {
    case class Action[Event, R[_], T](value: R[T], events: Observable[List[Event]]) extends ApiResult[Event, Nothing, R, T]
    case class StateAction[Event, State, R[_], T](state: Future[State], action: Action[Event, R, T]) extends ApiResult[Event, State, R, T]
  }
  import ApiResult._

  sealed trait ApiFunctionT[Event, State, T]
  sealed trait ApiFunction[Event, State, R[_], T] extends ApiFunctionT[Event, State, T] {
    def run(state: Future[State]): StateAction[Event, State, R, T] = f(state) match {
      case result: Action[Event, R, T] => StateAction(state, result)
      case result: StateAction[Event, State, R, T] => result
    }

    protected val f: Future[State] => ApiResult[Event, State, R, T]
  }
  object ApiFunction {
    case class Single[Event, State, T](protected val f: Future[State] => ApiResult[Event, State, Future, T]) extends ApiFunction[Event, State, Future, T]
    case class Stream[Event, State, T](protected val f: Future[State] => ApiResult[Event, State, Observable, T]) extends ApiFunction[Event, State, Observable, T]
  }
}

class ServerDsl[Event, State] {
  type ApiResult[R[_], T] = RawServerDsl.ApiResult[Event, State, R, T]
  object ApiResult {
    type Action[R[_], T] = RawServerDsl.ApiResult.Action[Event, R, T]
    type StateAction[R[_], T] = RawServerDsl.ApiResult.StateAction[Event, State, R, T]

    def apply[T](result: Future[T], events: Observable[List[Event]]): Action[Future, T] = RawServerDsl.ApiResult.Action(result, events)
    def apply[T](result: Future[T]): Action[Future, T] = apply(result, Observable.empty)
    def apply[T](result: Observable[T], events: Observable[List[Event]]): Action[Observable, T] = RawServerDsl.ApiResult.Action(result, events)
    def apply[T](result: Observable[T]): Action[Observable, T] = apply(result, Observable.empty)

    def apply[T](state: Future[State], result: Future[T], events: Observable[List[Event]]): StateAction[Future, T] = RawServerDsl.ApiResult.StateAction(state, apply(result, events))
    def apply[T](state: Future[State], result: Future[T]): StateAction[Future, T] = apply(state, result, Observable.empty)
    def apply[T](state: Future[State], result: Observable[T], events: Observable[List[Event]]): StateAction[Observable, T] = RawServerDsl.ApiResult.StateAction(state, apply(result, events))
    def apply[T](state: Future[State], result: Observable[T]): StateAction[Observable, T] = apply(state, result, Observable.empty)
  }

  type ApiFunctionT[T] = RawServerDsl.ApiFunctionT[Event, State, T]
  type ApiFunction[R[_], T] = RawServerDsl.ApiFunction[Event, State, R, T]
  object ApiFunction {
    type Single[T] = RawServerDsl.ApiFunction.Single[Event, State, T]
    type Stream[T] = RawServerDsl.ApiFunction.Stream[Event, State, T]

    def apply[T](result: => ApiResult[Future, T]): ApiFunction[Future, T] = new RawServerDsl.ApiFunction.Single(_ => result)
    def apply[T](result: Future[State] => ApiResult[Future, T]): ApiFunction[Future, T] = new RawServerDsl.ApiFunction.Single(result)
    def stream[T](result: => ApiResult[Observable, T]): ApiFunction[Observable, T] = new RawServerDsl.ApiFunction.Stream(_ => result)
    def stream[T](result: Future[State] => ApiResult[Observable, T]): ApiFunction[Observable, T] = new RawServerDsl.ApiFunction.Stream(result)
  }

  implicit def functor[Event, State](implicit scheduler: Scheduler): Functor[ApiFunctionT] = ??? //cats.derived.semi.functor[ApiFunctionT[Event, State, ?]]
}
