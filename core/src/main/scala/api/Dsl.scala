package covenant.api

import cats.Functor
import covenant.RequestResponse
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable

import scala.concurrent.Future

object ClientDsl {
  type Result[R[_], T] = R[T]
}

object RawServerDsl {
  case class ApiValue[+Event, +State, T](state: Option[Future[State]], value: Task[T], events: Observable[List[Event]])
  case class ApiStream[+Event, +State, T](state: Option[Future[State]], value: Observable[T], events: Observable[List[Event]])

//  case class ApiResult[+Event, +State, T](state: Future[State], value: RequestResponse[T], events: Observable[List[Event]])

//  trait ApiFunction[Event, State, T] {
//    def run(state: Future[State]): ApiResult[Event, State, T]
//  }
//  object ApiFunction {
//    class Single[Event, State, T](f: Future[State] => ApiValue[Event, State, T]) extends ApiFunction[Event, State, T] {
//      def run(state: Future[State]): ApiResult[Event, State, T] = {
//        val result = f(state)
//        val newState = result.state.getOrElse(state)
//        ApiResult(newState, RequestResponse.Single(result.value), result.events)
//      }
//    }
//    class Stream[Event, State, T](f: Future[State] => ApiStream[Event, State, T]) extends ApiFunction[Event, State, T] {
//      def run(state: Future[State]): ApiResult[Event, State, T] = {
//        val result = f(state)
//        val newState = result.state.getOrElse(state)
//        ApiResult(newState, RequestResponse.Stream(result.value), result.events)
//      }
//    }
//  }
}

class ServerDsl[Event, State] {
//  type ApiValue[T] = RawServerDsl.ApiValue[Event, State, T]
//  type ApiStream[T] = RawServerDsl.ApiStream[Event, State, T]
//  type ApiResult[T] = RawServerDsl.ApiResult[Event, State, T]
//
//  object Returns {
//    def apply[T](result: Task[T], events: Observable[List[Event]]): ApiValue[T] = RawServerDsl.ApiValue(None, result, events)
//    def apply[T](result: Task[T]): ApiValue[T] = apply(result, Observable.empty)
//    def apply[T](result: Future[T], events: Observable[List[Event]]): ApiValue[T] = apply(Task.fromFuture(result), events)
//    def apply[T](result: Future[T]): ApiValue[T] = apply(result, Observable.empty)
//    def apply[T](result: Observable[T], events: Observable[List[Event]]): ApiStream[T] = RawServerDsl.ApiStream(None, result, events)
//    def apply[T](result: Observable[T]): ApiStream[T] = apply(result, Observable.empty)
//
//    def apply[T](state: Future[State], result: Task[T], events: Observable[List[Event]]): ApiValue[T] = RawServerDsl.ApiValue(Some(state), result, events)
//    def apply[T](state: Future[State], result: Task[T]): ApiValue[T] = apply(state, result, Observable.empty)
//    def apply[T](state: Future[State], result: Future[T], events: Observable[List[Event]]): ApiValue[T] = apply(state, result, events)
//    def apply[T](state: Future[State], result: Future[T]): ApiValue[T] = apply(state, result, Observable.empty)
//    def apply[T](state: Future[State], result: Observable[T], events: Observable[List[Event]]): ApiStream[T] = RawServerDsl.ApiStream(Some(state), result, events)
//    def apply[T](state: Future[State], result: Observable[T]): ApiStream[T] = apply(state, result, Observable.empty)
//  }
//
//  type ApiFunction[T] = RawServerDsl.ApiFunction[Event, State, T]
//  object ApiFunction {
//    type Single[T] = RawServerDsl.ApiFunction.Single[Event, State, T]
//    type Stream[T] = RawServerDsl.ApiFunction.Stream[Event, State, T]
//
//    def apply[T](result: => ApiValue[T]): ApiFunction.Single[T] = new RawServerDsl.ApiFunction.Single(_ => result)
//    def apply[T](result: Future[State] => ApiValue[T]): ApiFunction.Single[T] = new RawServerDsl.ApiFunction.Single(result)
//    def stream[T](result: => ApiStream[T]): ApiFunction.Stream[T] = new RawServerDsl.ApiFunction.Stream(_ => result)
//    def stream[T](result: Future[State] => ApiStream[T]): ApiFunction.Stream[T] = new RawServerDsl.ApiFunction.Stream(result)
//  }
//
//  implicit def functor(implicit scheduler: Scheduler): Functor[ApiFunction] = ??? //cats.derived.semi.functor[ApiFunctionT[Event, State, ?]]
}
