package test

import org.scalatest._

import covenant.http._
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.RouteResult._
import akka.stream.ActorMaterializer
import akka.actor.ActorSystem
import chameleon.ext.boopickle._
import boopickle.Default._
import java.nio.ByteBuffer

import cats.implicits._

import scala.concurrent.Future

class HttpSpec extends AsyncFreeSpec with MustMatchers with BeforeAndAfterAll {

  trait Api {
    def fun(a: Int): Future[Int]
    @PathName("funWithDefault")
    def fun(a: Int, b: Int): Future[Int] = fun(a + b)
  }
  object ApiImpl extends Api {
    def fun(a: Int): Future[Int] = Future.successful(a)
  }

  implicit val system = ActorSystem("akkahttp")
  implicit val materializer = ActorMaterializer()
  val port = 9998

 "run" in {
    object Backend {
      val router = Router[ByteBuffer, Future]
        .route[Api](ApiImpl)

      def run() = {
        Http().bindAndHandle(router.asHttpRoute, interface = "0.0.0.0", port = port)
      }
    }

    object Frontend {
      val client = HttpClient[ByteBuffer](s"http://localhost:$port")
      val api = client.wire[Api]
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
