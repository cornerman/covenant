package test

import org.scalatest._

import covenant.core.api._
import covenant.http._, ByteBufferImplicits._
import sloth._
import chameleon.ext.boopickle._
import boopickle.Default._
import java.nio.ByteBuffer

import akka.http.scaladsl.Http
import akka.http.scaladsl.server.RouteResult._
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.actor.ActorSystem
import monix.reactive.Observable

import cats.implicits._
import cats.derived.auto.functor._

import scala.concurrent.Future

class HttpSpec extends AsyncFreeSpec with MustMatchers with BeforeAndAfterAll {
  trait Api[Result[_]] {
    def fun(a: Int): Result[Int]
    @PathName("funWithDefault")
    def fun(a: Int, b: Int): Result[Int] = fun(a + b)
  }

  object FutureApiImpl extends Api[Future] {
    def fun(a: Int): Future[Int] = Future.successful(a)
  }

  trait StreamAndFutureApi[Result[R[_], _]] {
    def foo(a: Int): Result[Future, Int]
    def bar(a: Int): Result[Observable, Int]
  }

  object DslApiImpl extends StreamAndFutureApi[Dsl.ApiFunction] {
    import Dsl._

    def foo(a: Int): ApiFunction[Future, Int] = ApiFunction { state =>
      ApiResult(Future.successful(a))
    }

    def bar(a: Int): ApiFunction[Observable, Int] = ApiFunction.stream { state =>
      ApiResult(Observable(a))
    }
  }

  //TODO generalize over this structure, can implement requesthander? --> apidsl
  type Event = String
  type State = String

  case class ApiError(msg: String)

  object Dsl extends ApiDsl[Event, ApiError, State]

  implicit val system = ActorSystem("akkahttp")
  implicit val materializer = ActorMaterializer()

 "simple run" in {
    val port = 9989

    object Backend {
      val router = Router[ByteBuffer, Future]
        .route[Api[Future]](FutureApiImpl)

      def run() = {
        Http().bindAndHandle(AkkaHttpRoute.fromFutureRouter(router), interface = "0.0.0.0", port = port)
      }
    }

    object Frontend {
      val client = HttpClient[ByteBuffer](s"http://localhost:$port")
      val api = client.wire[Api[Future]]
    }

    Backend.run()

    for {
      fun <- Frontend.api.fun(1)
      fun2 <- Frontend.api.fun(1, 2)
    } yield {
      fun mustEqual 1
      fun2 mustEqual 3
    }
  }

 "run" in {
   import covenant.http.api._
   import monix.execution.Scheduler.Implicits.global

   val port = 9988

   val api = new HttpApiConfiguration[Event, ApiError, State] {
     val dsl = Dsl
     override def requestToState(request: HttpRequest): Future[State] = Future.successful(request.toString)
     override def publishEvents(events: Observable[List[Event]]): Unit = ()
   }

   object Backend {
     val router = Router[ByteBuffer, Dsl.ApiFunctionT]
       .route[StreamAndFutureApi[Dsl.ApiFunction]](DslApiImpl)

     def run() = {
       val route = AkkaHttpRoute.fromApiRouter(router, api)
       Http().bindAndHandle(route, interface = "0.0.0.0", port = port)
     }
   }

   object Frontend {
     val client = HttpClient[ByteBuffer](s"http://localhost:$port")
     val api = client.wire[Api[Future]]
   }

   Backend.run()

   for {
     fun <- Frontend.api.fun(1)
     fun2 <- Frontend.api.fun(1, 2)
   } yield {
     fun mustEqual 1
     fun2 mustEqual 3
   }
 }

  override def afterAll(): Unit = {
    system.terminate()
    ()
  }
}
