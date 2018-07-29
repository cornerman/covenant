package test

import java.nio.ByteBuffer

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import boopickle.Default._
import cats.~>
import chameleon.ext.boopickle._
import covenant.RequestResponse.{PureValue, StateFunction}
import covenant._
import covenant.api.ServerDsl
import covenant.core.{ResultTypes}
import covenant.http._
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import monix.reactive.subjects.PublishSubject
import org.scalatest._
import sloth._

import scala.concurrent.Future

class HttpSpec extends AsyncFreeSpec with MustMatchers with BeforeAndAfterAll {
  override implicit def executionContext = Scheduler.global

  trait Api[Result[_]] {
    def fun(a: Int): Result[Int]
    def fun(a: Int, b: Int): Result[Int] = fun(a + b)
  }

  object FutureApiImpl extends Api[Future] {
    def fun(a: Int): Future[Int] = Future.successful(a)
  }
  object ObservableApiImpl extends Api[Observable] {
    def fun(a: Int): Observable[Int] = Observable(a,2,3)
  }


  case class State(user: Option[String])
  trait MixedApi[Fun[F[_], T]] {
    def single(a: Int): Task[Int]
    def stream(a: Int, b: Int): Observable[Int]
    def getState(): Fun[Task, State]
  }

  object MixedApiImpl extends MixedApi[ResultTypes.WithState[State]#Apply] {
    override def single(a: Int) = Task.pure(a)
    override def stream(a: Int, b: Int) = Observable(a, b)
    override def getState() = (a: State) => Task.pure(a)
  }


//  trait Api[Fun[F[_]]] {
//    def fun(a: Int): Result[Int]
//    def fun(a: Int, b: Int): Result[Int] = fun(a + b)
//  }
//
//  object FutureApiImpl extends Api[Future] {
//    def fun(a: Int): Future[Int] = Future.successful(a)
//  }
//  object ObservableApiImpl extends Api[Observable] {
//    def fun(a: Int): Observable[Int] = Observable(a,2,3)
//  }


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

  implicit val system = ActorSystem("akkahttp")
  implicit val materializer = ActorMaterializer()

 "simple run" in {
    val port = 9989

    object Backend {
      val router = RequestRouter[ByteBuffer, HttpErrorCode]
        .route[Api[Future]](FutureApiImpl)

      def run() = {
        Http().bindAndHandle(AkkaHttpRoute.fromRouter(router), interface = "0.0.0.0", port = port)
      }
    }

    object Frontend {
      val transport = AkkaHttpRequestTransport[ByteBuffer](s"http://localhost:$port")
      val client = RequestClient(transport)
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
      val router = RequestRouter[ByteBuffer, HttpErrorCode]
        .route[Api[Observable]](ObservableApiImpl)

      def run() = {
        Http().bindAndHandle(AkkaHttpRoute.fromRouter(router), interface = "0.0.0.0", port = port)
      }
    }

    object Frontend {
      val transport = AkkaHttpRequestTransport[ByteBuffer](s"http://localhost:$port")
      val client = RequestClient(transport)
      val api = client.wire[Api[Observable]]
    }

    Backend.run()

    val funs1 = Frontend.api.fun(13).toListL.runAsync
    val funs2 = Frontend.api.fun(7, 9).toListL.runAsync

    for {
      funs1 <- funs1
      funs2 <- funs2
    } yield {
      funs1 mustEqual List(13, 2, 3)
      funs2 mustEqual List(16, 2, 3)
    }
  }

  "mixed api with state" in {
    val port = 9988

    object Backend {

      val router = RequestRouter.withState[ByteBuffer, HttpErrorCode, State]
        .route[MixedApi[ResultTypes.WithState[State]#Apply]](MixedApiImpl)

      def extractNameFromHeaders(headers: Seq[HttpHeader]): State = {
        headers.find(_.name == "user").fold(State(None))(header => State(Some(header.value)))
      }

      def run() = {
        val route = AkkaHttpRoute.fromRouterWithState[ByteBuffer, State](router, extractNameFromHeaders)
        Http().bindAndHandle(route, interface = "0.0.0.0", port = port)
      }
    }

    object Frontend {
      val headers = PublishSubject[List[HttpHeader]]()
      val transport = AkkaHttpRequestTransport[ByteBuffer](s"http://localhost:$port")
      val client = Client(transport.requestWith(headers))
      val api = client.wire[MixedApi[ResultTypes.Apply]]
    }

    Backend.run()

    for {
      _ <- Frontend.headers.onNext(Nil)
      fun <- Frontend.api.single(13).runAsync
      fun2 <- Frontend.api.stream(99, 71).toListL.runAsync
      state1 <- Frontend.api.getState().runAsync
      _ <- Frontend.headers.onNext(HttpHeader("user", "peter") :: Nil)
      state2 <- Frontend.api.getState().runAsync
    } yield {
      state1.user mustEqual None
      state2.user mustEqual Some("peter")
      fun mustEqual 13
      fun2 mustEqual List(99, 71)
    }
  }

  override def afterAll(): Unit = {
    system.terminate()
    ()
  }
}
