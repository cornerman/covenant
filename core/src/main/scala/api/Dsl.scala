package covenant.core.api

import scala.concurrent.{ExecutionContext, Future}
import scala.annotation.tailrec
import scala.util.control.NonFatal
import cats.implicits._
import monix.reactive.Observable

trait ApiDsl[Event, ErrorType, State] {
  def applyEventsToState(state: State, events: Seq[Event]): State
  def unhandledException(t: Throwable): ErrorType

  //TODO better availablity of types not in obj, package object because of type alias? but currently name clash with ApiDsl.Effect/Action => rename
  object ApiData {
    case class Action[+T](data: Either[ErrorType, T], asyncEvents: Observable[Seq[Event]] = Observable.empty)
    case class Effect[+T](events: Seq[Event], action: Action[T])

    implicit def ActionIsEffect[T](action: Action[T]): Effect[T] = Effect(Seq.empty, action)

    type MonadError[F[_]] = cats.MonadError[F, ErrorType]
    def MonadError[F[_]](implicit m: cats.MonadError[F, ErrorType]) = m

    implicit def actionMonadError: MonadError[Action] = new MonadError[Action] {
      def pure[A](a: A): Action[A] = Action(Right(a))
      def raiseError[A](e: ErrorType): Action[A] = Action(Left(e))
      def handleErrorWith[A](e: Action[A])(f: ErrorType => Action[A]): Action[A] = e.data.fold(f, _ => e)
      def flatMap[A, B](e: Action[A])(f: A => Action[B]): Action[B] = e.data.fold[Action[B]]((err) => e.copy(data = Left(err)), { res =>
        val action = f(res)
        val newEvents = Observable.concat(e.asyncEvents, action.asyncEvents)
        action.copy(asyncEvents = newEvents)
      })
      @tailrec
      def tailRecM[A, B](a: A)(f: A => Action[Either[A,B]]): Action[B] = {
        val action = f(a)
        action.data match {
          case Left(e) => action.copy(data = Left(e))
          case Right(Left(next)) => tailRecM(next)(f)
          case Right(Right(b)) => action.copy(data = Right(b))
        }
      }
    }

    implicit def effectMonadError: MonadError[Effect] = new MonadError[Effect] {
      def pure[A](a: A): Effect[A] = ActionIsEffect(MonadError[Action].pure(a))
      def raiseError[A](e: ErrorType): Effect[A] = ActionIsEffect(MonadError[Action].raiseError(e))
      def handleErrorWith[A](e: Effect[A])(f: ErrorType => Effect[A]): Effect[A] = e.action.data.fold(f, _ => e)
      def flatMap[A, B](e: Effect[A])(f: A => Effect[B]): Effect[B] = e.action.data.fold[Effect[B]](err => e.copy(action = e.action.copy(data = Left(err))), { res =>
        val effect = f(res)
        val newEvents = e.events ++ effect.events
        effect.copy(events = newEvents)
      })
      @tailrec
      def tailRecM[A, B](a: A)(f: A => Effect[Either[A,B]]): Effect[B] = {
        val effect = f(a)
        effect.action.data match {
          case Left(e) => effect.copy(action = effect.action.copy(data = Left(e)))
          case Right(Left(next)) => tailRecM(next)(f)
          case Right(Right(b)) => effect.copy(action = effect.action.copy(data = Right(b)))
        }
      }
    }
  }

  case class ApiFunction[T](run: Future[State] => ApiFunction.Response[T])
  object ApiFunction {
    case class ReturnValue[T](result: Either[ErrorType, T], events: Seq[Event])
    case class Response[T](state: Future[State], value: Future[ReturnValue[T]], asyncEvents: Observable[Seq[Event]])
    object Response {
      private val handleUserException: PartialFunction[Throwable, ErrorType] = {
        case NonFatal(e) =>
          scribe.error(s"Exception in API method: $e", e)
          unhandledException(e)
      }
      private val handleDelayedUserException: PartialFunction[Throwable, Observable[Seq[Event]]] = {
        case NonFatal(e) =>
          scribe.error(s"Exception in API delayed events: $e", e)
          Observable.empty
      }

      def action[T](oldState: Future[State], rawAction: Future[ApiData.Action[T]])(implicit ec: ExecutionContext): Response[T] = {
        val action = rawAction.recover(handleUserException andThen (err => ApiData.Action(Left(err))))
        val safeDelayEvents = Observable.fromFuture(action).flatMap(_.asyncEvents).onErrorRecoverWith(handleDelayedUserException)
        Response(oldState, action.map(action => ReturnValue(action.data, Seq.empty)), safeDelayEvents)
      }
      def effect[T](oldState: Future[State], rawEffect: Future[ApiData.Effect[T]])(implicit ec: ExecutionContext): Response[T] = {
        val effect = rawEffect.recover(handleUserException andThen (err => ApiData.ActionIsEffect(ApiData.Action(Left(err)))))
        val newState = applyFutureEventsToState(oldState, effect.map(_.events))
        val safeDelayEvents = Observable.fromFuture(effect).flatMap(_.action.asyncEvents).onErrorRecoverWith(handleDelayedUserException)
        Response(newState, effect.map(e => ReturnValue(e.action.data, e.events)), safeDelayEvents)
      }
    }
    trait Factory[F[_]] {
      def apply[T](f: State => Future[F[T]])(implicit ec: ExecutionContext): ApiFunction[T]
      def apply[T](f: => Future[F[T]])(implicit ec: ExecutionContext): ApiFunction[T]
    }

    def redirect[T](api: ApiFunction[T])(f: State => Future[Seq[Event]])(implicit ec: ExecutionContext): ApiFunction[T] = ApiFunction { state =>
      val events = state.flatMap(f)
      def newState = applyFutureEventsToState(state, events)
      val response = api.run(newState)
      val newValue = for {
        events <- events
        value <- response.value
      } yield value.copy(events = events ++ value.events)

      response.copy(value = newValue)
    }

    protected def applyFutureEventsToState(state: Future[State], events: Future[Seq[Event]])(implicit ec: ExecutionContext): Future[State] = for {
      events <- events
      state <- state
    } yield if (events.isEmpty) state else applyEventsToState(state, events)

    implicit val apiReturnValueFunctor = cats.derive.functor[ReturnValue]
    implicit def apiResponseFunctor(implicit ec: ExecutionContext) = cats.derive.functor[Response]
    implicit def apiFunctionFunctor(implicit ec: ExecutionContext) = cats.derive.functor[ApiFunction]
  }

  object Action extends ApiFunction.Factory[ApiData.Action] {
    def apply[T](f: State => Future[ApiData.Action[T]])(implicit ec: ExecutionContext): ApiFunction[T] = ApiFunction(s => ApiFunction.Response.action(s, s.flatMap(f)))
    def apply[T](f: => Future[ApiData.Action[T]])(implicit ec: ExecutionContext): ApiFunction[T] = ApiFunction(s => ApiFunction.Response.action(s, f))
  }
  object Effect extends ApiFunction.Factory[ApiData.Effect] {
    def apply[T](f: State => Future[ApiData.Effect[T]])(implicit ec: ExecutionContext): ApiFunction[T] = ApiFunction(s => ApiFunction.Response.effect(s, s.flatMap(f)))
    def apply[T](f: => Future[ApiData.Effect[T]])(implicit ec: ExecutionContext): ApiFunction[T] = ApiFunction(s => ApiFunction.Response.effect(s, f))
  }
  object Returns {
    def apply[T](result: T, events: Seq[Event], asyncEvents: Observable[Seq[Event]]): ApiData.Effect[T] = ApiData.Effect(events, apply(result, asyncEvents))
    def apply[T](result: T, events: Seq[Event]): ApiData.Effect[T] = ApiData.Effect(events, apply(result))
    def apply[T](result: T, asyncEvents: Observable[Seq[Event]]): ApiData.Action[T] = ApiData.Action(Right(result), asyncEvents)
    def apply[T](result: T): ApiData.Action[T] = ApiData.Action(Right(result))

    def error(failure: ErrorType, events: Seq[Event]): ApiData.Effect[Nothing] = ApiData.Effect(events, error(failure))
    def error(failure: ErrorType): ApiData.Action[Nothing] = ApiData.Action(Left(failure))
  }


  implicit def ValueIsAction[T](value: T): ApiData.Action[T] = Returns(value)
  implicit def FailureIsAction[T](failure: ErrorType): ApiData.Action[T] = Returns.error(failure)
  implicit def FutureValueIsAction[T](value: Future[T])(implicit ec: ExecutionContext): Future[ApiData.Action[T]] = value.map(ValueIsAction)
  implicit def FutureFailureIsAction[T](failure: Future[ErrorType])(implicit ec: ExecutionContext): Future[ApiData.Action[T]] = failure.map(FailureIsAction)
}
