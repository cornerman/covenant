package test

import java.nio.ByteBuffer

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import boopickle.Default._
import cats.data.EitherT
import chameleon.ext.boopickle._
import covenant.ws.{AkkaWsRequestTransport, AkkaWsRoute}
import covenant.{RequestClient, RequestRouter}
import monix.execution.Scheduler
import monix.reactive.Observable
import mycelium.client._
import org.scalatest._
import sloth._

import scala.concurrent.Future

class WsSpec extends AsyncFreeSpec with MustMatchers with BeforeAndAfterAll {
  override implicit def executionContext = Scheduler.global

  trait Api[Result[_]] {
    def fun(a: Int): Result[Int]
    def fun(a: Int, b: Int): Result[Int] = fun(a + b)
  }

  object FutureApiImpl extends Api[Future] {
    def fun(a: Int): Future[Int] = Future.successful(a)
  }

  object ObservableApiImpl extends Api[Observable] {
    def fun(a: Int): Observable[Int] = Observable.fromIterable(List(a, a * 2, a * 3))
  }

//  object DslApiImpl extends Api[Single, Stream] {
//    import Dsl._
//
      // def single: Future[Int] = Future.successful(a)
      // def fun(a: Int): Observable[Int] = Observable.fromIterable(List(a, a * 2, a * 3))
//    def fun(a: Int): ApiFunction[Int] = new ApiFunction.Single { state =>
//      Future.successful(a)
//    }
//  }
//
//  //TODO generalize over this structure, can implement requesthander? --> apidsl
//  type Event = String
//  type State = String
//
//  case class ApiValue[T](result: T, events: List[Event])
//  case class ApiResult[T](state: Future[State], value: Future[ApiValue[T]])
//  type ApiResultFun[T] = Future[State] => ApiResult[T]
//
 case class ApiError(msg: String)
 object ApiError {
  implicit def clientFailureConvert = new ClientFailureConvert[ApiError] {
    def convert(failure: ClientFailure) = ApiError(s"Sloth failure: $failure")
  }
 }
//
//  object Dsl extends ApiDsl[ApiError, Event, State] {
//    override def applyEventsToState(state: State, events: Seq[Event]): State = state + " " + events.mkString(",")
//    override def unhandledException(t: Throwable): ApiError = ApiError(t.getMessage)
//  }
//  //
//
 implicit val system = ActorSystem("mycelium")
 implicit val materializer = ActorMaterializer()

 override def afterAll(): Unit = {
   system.terminate()
   ()
 }

 "simple run" in {
     val port = 9990

    object Backend {
      val router = RequestRouter[ByteBuffer]
        .route[Api[Future]](FutureApiImpl)

      def run() = {
        val route = AkkaWsRoute.fromRouter[ByteBuffer, ApiError](router)
        Http().bindAndHandle(route, interface = "0.0.0.0", port = port)
      }
    }

    object Frontend {
      type Result[T] = EitherT[Future, ApiError, T]
      val transport = AkkaWsRequestTransport[ByteBuffer, ApiError](s"ws://localhost:$port/ws")
      val client = RequestClient[ByteBuffer, ApiError](transport)
      val api: Api[Result] = client.wire[Api[Result]]
    }

    Backend.run()

    for {
      fun <- Frontend.api.fun(1).value
      fun2 <- Frontend.api.fun(1, 2).value
    } yield {
      fun mustEqual Right(1)
      fun2 mustEqual Right(3)
    }
  }

  "stream run" in {
    val port = 9991

    object Backend {
      val router = RequestRouter[ByteBuffer]
        .route[Api[Observable]](ObservableApiImpl)

      def run() = {
        val route = AkkaWsRoute.fromRouter[ByteBuffer, ApiError](router)
        Http().bindAndHandle(route, interface = "0.0.0.0", port = port)
      }
    }

    object Frontend {
      val config = WebsocketClientConfig()
      val transport = AkkaWsRequestTransport[ByteBuffer, ApiError](s"ws://localhost:$port/ws")
      val client = RequestClient[ByteBuffer, ApiError](transport.requestWith(timeout = None))
      val api: Api[Observable] = client.wire[Api[Observable]]
    }

    Backend.run()

    val funs1 = Frontend.api.fun(1).toListL.runAsync
    val funs2 = Frontend.api.fun(1, 2).toListL.runAsync

    for {
      funs1 <- funs1
      funs2 <- funs2
    } yield {
      funs1 mustEqual List(1, 2, 3)
      funs2 mustEqual List(3, 6, 9)
    }
  }

// "dsl run" in {
//   import covenant.ws.api._
//   import monix.execution.Scheduler.Implicits.global
//
//   val port = 9992
//
//   val api = new WsApiConfigurationWithDefaults[Event, ApiError, State] {
//     override def dsl = Dsl
//     override def initialState: State = ""
//     override def isStateValid(state: State): Boolean = true
//     override def serverFailure(error: ServerFailure): ApiError = ApiError(error.toString)
//   }
//
//   object Backend {
//     val router = Router[ByteBuffer, Dsl.ApiFunction]
//       .route[Api[Dsl.ApiFunction]](DslApiImpl)
//
//     def run() = {
//       val config = WebsocketServerConfig(bufferSize = 5, overflowStrategy = OverflowStrategy.fail)
//       val route = AkkaWsRoute.fromApiRouter(router, config, api)
//       Http().bindAndHandle(route, interface = "0.0.0.0", port = port)
//     }
//   }
//
//   object Frontend {
//     val config = WebsocketClientConfig()
//     val client = WsClient[ByteBuffer, ApiError](s"ws://localhost:$port/ws", config)
//     val api = client.sendWithDefault.wire[Api[Future]]
//   }
//
//   Backend.run()
//
//   for {
//     fun <- Frontend.api.fun(1)
//     fun2 <- Frontend.api.fun(1, 2)
//   } yield {
//     fun mustEqual 1
//     fun2 mustEqual 3
//   }
// }
}
