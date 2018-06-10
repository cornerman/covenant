package covenant.core.api

import cats.{Functor, Id}

import scala.concurrent.{ExecutionContext, Future}
import scala.annotation.tailrec
import scala.util.control.NonFatal
import cats.implicits._
import monix.execution.Scheduler
import monix.reactive.Observable

object ClientDsl {
  type Result[R[_], T] = R[T]
}

trait ApiDsl[Event, ErrorType, State] {

  sealed trait ApiResultT[T]
  sealed trait ApiResult[R[_], T] extends ApiResultT[T]
  object ApiResult {

    case class Action[R[_], T] private(value: R[T], events: Observable[List[Event]]) extends ApiResult[R, T]
    case class StateAction[R[_], T] private(state: Future[State], action: Action[R, T]) extends ApiResult[R, T]

    def apply[T](result: Future[T], events: Observable[List[Event]]): Action[Future, T] = Action(result, events)
    def apply[T](result: Future[T]): Action[Future, T] = apply(result, Observable.empty)
    def apply[T](result: Observable[T], events: Observable[List[Event]]): Action[Observable, T] = Action(result, events)
    def apply[T](result: Observable[T]): Action[Observable, T] = apply(result, Observable.empty)
    def apply[T](state: Future[State], result: Future[T], events: Observable[List[Event]]): StateAction[Future, T] = StateAction(state, Action(result, events))
    def apply[T](state: Future[State], result: Future[T]): StateAction[Future, T] = apply(state, result, Observable.empty)
    def apply[T](state: Future[State], result: Observable[T], events: Observable[List[Event]]): StateAction[Observable, T] = StateAction(state, Action(result, events))
    def apply[T](state: Future[State], result: Observable[T]): StateAction[Observable, T] = apply(state, result, Observable.empty)
  }
  import ApiResult._

  sealed trait ApiFunctionT[T]

  sealed trait ApiFunction[R[_], T] extends ApiFunctionT[T] {
    def run(state: Future[State]): StateAction[R, T] = f(state) match {
      case result: Action[R, T] => StateAction(state, result)
      case result: StateAction[R, T] => result
    }

    protected val f: Future[State] => ApiResult[R, T]
  }
  object ApiFunction {
    case class Single[T](protected val f: Future[State] => ApiResult[Future, T]) extends ApiFunction[Future, T]
    case class Stream[T](protected val f: Future[State] => ApiResult[Observable, T]) extends ApiFunction[Observable, T]

    def apply[T](result: => ApiResult[Future, T]): ApiFunction[Future, T] = new Single(_ => result)
    def apply[T](result: Future[State] => ApiResult[Future, T]): ApiFunction[Future, T] = new Single(result)
    def stream[T](result: => ApiResult[Observable, T]): ApiFunction[Observable, T] = new Stream(_ => result)
    def stream[T](result: Future[State] => ApiResult[Observable, T]): ApiFunction[Observable, T] = new Stream(result)
  }

  object ApiFunctionT {
    implicit def functor(implicit scheduler: Scheduler): Functor[ApiFunctionT] = cats.derived.semi.functor[ApiFunctionT]
  }
}
