package test

import java.nio.ByteBuffer

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import boopickle.Default._
import chameleon.ext.boopickle._
import covenant.{DefaultLogHandler, RequestRouter}
import covenant.api.ServerDsl
import covenant.http._
import monix.execution.Scheduler
import monix.reactive.Observable
import org.scalatest._
import sloth._

import scala.concurrent.Future

class HttpSpec extends AsyncFreeSpec with MustMatchers with BeforeAndAfterAll {
  override implicit def executionContext = Scheduler.global

  trait Api[Result[_]] {
    def fun(a: Int): Result[Int]
    @PathName("funWithDefault")
    def fun(a: Int, b: Int): Result[Int] = fun(a + b)
  }

  object FutureApiImpl extends Api[Future] {
    def fun(a: Int): Future[Int] = Future.successful(a)
  }
  object ObservableApiImpl extends Api[Observable] {
    def fun(a: Int): Observable[Int] = Observable(a,2,3)
  }

//  trait StreamAndFutureApi[Result[R[_], _]] {
//    def foo(a: Int): Result[Future, Int]
//    def bar(a: Int): Result[Observable, Int]
//  }

//  object DslApiImpl extends StreamAndFutureApi[Dsl.ApiFunction] {
//
//    def foo(a: Int): ApiFunction[Future, Int] = ApiFunction { state =>
//      ApiResult(Future.successful(a))
//    }
//
//    def bar(a: Int): ApiFunction[Observable, Int] = ApiFunction.stream { state =>
//      ApiResult(Observable(a))
//    }
//  }

  //TODO generalize over this structure, can implement requesthander? --> apidsl
  type Event = String
  type State = String

  case class ApiError(msg: String)

  object Dsl extends ServerDsl[Event, State]

  implicit val system = ActorSystem("akkahttp")
  implicit val materializer = ActorMaterializer()

 "simple run" in {
    val port = 9989

    object Backend {
      val router = RequestRouter[ByteBuffer]
        .route[Api[Future]](FutureApiImpl)

      def run() = {
        Http().bindAndHandle(AkkaHttpRoute.fromRouter(router), interface = "0.0.0.0", port = port)
      }
    }

    object Frontend {
      val transport = AkkaHttpRequestTransport[ByteBuffer](s"http://localhost:$port")
      val client = Client(transport.flattenError, DefaultLogHandler)
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

 "stream run" in {
    val port = 9987

    object Backend {
      val router = RequestRouter[ByteBuffer]
        .route[Api[Observable]](ObservableApiImpl)

      def run() = {
        Http().bindAndHandle(AkkaHttpRoute.fromRouter(router), interface = "0.0.0.0", port = port)
      }
    }

    object Frontend {
      val transport = AkkaHttpRequestTransport[ByteBuffer](s"http://localhost:$port")
      val client = Client(transport.flattenError, DefaultLogHandler)
      val api = client.wire[Api[Observable]]
    }

    Backend.run()

    val funs1 = Frontend.api.fun(13).foldLeftL[List[Int]](Nil)((l,i) => l :+ i).runAsync
    val funs2 = Frontend.api.fun(7, 9).foldLeftL[List[Int]](Nil)((l,i) => l :+ i).runAsync
    Thread.sleep(6000)

    for {
      funs1 <- funs1
      funs2 <- funs2
    } yield {
      funs1 mustEqual List(13, 2, 3)
      funs2 mustEqual List(16, 2, 3)
    }
  }

 // "api run" in {
 //   import covenant.http.api._
 //   import monix.execution.Scheduler.Implicits.global

 //   val port = 9988

 //   val api = new HttpApiConfiguration[Event, ApiError, State] {
 //     val dsl = Dsl
 //     override def requestToState(request: HttpRequest): Future[State] = Future.successful(request.toString)
 //     override def publishEvents(events: Observable[List[Event]]): Unit = ()
 //   }

 //   object Backend {
 //     val router = Router[ByteBuffer, Dsl.ApiFunctionT]
 //       .route[StreamAndFutureApi[Dsl.ApiFunction]](DslApiImpl)

 //     def run() = {
 //       val route = AkkaHttpRoute.fromApiRouter(router, api)
 //       Http().bindAndHandle(route, interface = "0.0.0.0", port = port)
 //     }
 //   }

 //   object Frontend {
 //     val transport = HttpRequestTransport[ByteBuffer]("http://localhost:$port")
 //     val client = Client(transport)
 //     val api = client.wire[Api[Future]]
 //   }

 //   Backend.run()

 //   for {
 //     fun <- Frontend.api.fun(1)
 //     fun2 <- Frontend.api.fun(1, 2)
 //   } yield {
 //     fun mustEqual 1
 //     fun2 mustEqual 3
 //   }
 // }

  override def afterAll(): Unit = {
    system.terminate()
    ()
  }
}
