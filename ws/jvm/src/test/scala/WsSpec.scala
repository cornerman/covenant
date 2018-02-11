package test

import org.scalatest._

import covenant.ws._
import sloth._
import chameleon.ext.boopickle._
import boopickle.Default._
import java.nio.ByteBuffer
import mycelium.client._
import mycelium.server._

import akka.http.scaladsl.Http
import akka.http.scaladsl.server.RouteResult._
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.actor.ActorSystem

import cats.implicits._

import scala.concurrent.Future

class WsSpec extends AsyncFreeSpec with MustMatchers with BeforeAndAfterAll {
  trait Api[Result[_]] {
    def fun(a: Int): Result[Int]
    @PathName("funWithDefault")
    def fun(a: Int, b: Int): Result[Int] = fun(a + b)
  }

  //TODO generalize over this structure, can implement requesthander? --> apidsl
  type Event = String
  type State = String

  case class ApiValue[T](result: T, events: List[Event])
  case class ApiResult[T](state: Future[State], value: Future[ApiValue[T]])
  type ApiResultFun[T] = Future[State] => ApiResult[T]

  sealed trait ApiError
  case class SlothError(msg: String) extends ApiError

  implicit val apiValueFunctor = cats.derive.functor[ApiValue]
  implicit val apiResultFunctor = cats.derive.functor[ApiResult]
  implicit val apiResultFunFunctor = cats.derive.functor[ApiResultFun]
  //

  object ApiImpl extends Api[ApiResultFun] {
    def fun(a: Int): ApiResultFun[Int] =
      state => ApiResult(state, Future.successful(ApiValue(a, Nil)))
  }

  implicit val system = ActorSystem("mycelium")
  implicit val materializer = ActorMaterializer()

  val port = 9999

  override def afterAll(): Unit = {
    system.terminate()
    ()
  }

 "run" in {
    object Backend {
      val router = Router[ByteBuffer, ApiResultFun]
        .route[Api[ApiResultFun]](ApiImpl)

      val config = WebsocketServerConfig(bufferSize = 5, overflowStrategy = OverflowStrategy.fail)
      val handler = new SimpleRequestHandler[ByteBuffer, Event, ApiError, State] {
        def initialState = Future.successful("empty")
        def onRequest(state: Future[State], path: List[String], payload: ByteBuffer) = {
          router(Request(path, payload)).toEither match {
            case Right(fun) =>
              val res = fun(state)
              Response(res.state, res.value.map(v => ReturnValue(Right(v.result), v.events)))
            case Left(err) =>
              Response(state, Future.successful(ReturnValue(Left(SlothError(err.toString)))))
          }
        }
      }

      def run() = {
        Http().bindAndHandle(router.asWsRoute(config, handler), interface = "0.0.0.0", port = port)
      }
    }

    object Frontend {
      val akkaConfig = AkkaWebsocketConfig(bufferSize = 5, overflowStrategy = OverflowStrategy.fail)
      val mycelium = WebsocketClient[ByteBuffer, Event, ApiError](
        new AkkaWebsocketConnection(akkaConfig), WebsocketClientConfig(), new IncidentHandler[Event])

      val client = WsClient[ByteBuffer](mycelium)
      val api = client.wire[Api[Future]]

      def run() = {
        mycelium.run(s"ws://localhost:$port/ws")
      }
    }

    Backend.run()
    Frontend.run()

    for {
      fun <- Frontend.api.fun(1)
      fun2 <- Frontend.api.fun(1, 2)
    } yield {
      fun mustEqual 1
      fun2 mustEqual 3
    }
  }
}
