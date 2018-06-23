package covenant

import cats.{Functor, ~>}
import monix.eval.Task
import monix.reactive.Observable
import sloth._

import scala.concurrent.Future

//TODO: allow eithert is more general concept, giving way to returning errors in router
sealed trait RequestResponse[T] extends Any
object RequestResponse {
  case class Single[T](task: Task[T]) extends AnyVal with RequestResponse[T]
  case class Stream[T](observable: Observable[T]) extends AnyVal with RequestResponse[T]

  implicit val functor: Functor[RequestResponse] = cats.derived.semi.functor[RequestResponse]

  implicit val fromTask: ResultMapping[Task, RequestResponse] = ResultMapping(Lambda[Task ~> RequestResponse](RequestResponse.Single(_)))
  implicit val fromFuture: ResultMapping[Future, RequestResponse] = ResultMapping(Lambda[Future ~> RequestResponse](f => RequestResponse.Single(Task.fromFuture(f))))
  implicit val fromObservable: ResultMapping[Observable, RequestResponse] = ResultMapping(Lambda[Observable ~> RequestResponse](RequestResponse.Stream(_)))

  // implicit def toEitherT[Result[_], ErrorType](implicit mapping: ResultMapping[RequestOperation, Result]): ResultMapping[EitherT[RequestOperation, ErrorType, ?], EitherT[Result, ErrorType, ?]] = ResultMapping(Lambda[EitherT[RequestOperation, ErrorType, ?] ~> EitherT[Result, ErrorType, ?]](_.mapK(mapping)))
}

object RequestRouter {
  def apply[PickleType]: Router[PickleType, RequestResponse] = Router[PickleType, RequestResponse]
}
