// package test

// import org.scalatest._

// import covenant.http._
// import chameleon.ext.boopickle._
// import boopickle.Default._
// import java.nio.ByteBuffer

// import scala.concurrent.Future

// class HttpSpec extends AsyncFreeSpec with MustMatchers with BeforeAndAfterAll {

//   trait Api {
//     def fun(a: Int): Future[Int]
//   }
//   val port = 9997

//  "run" in {
//     object Frontend {
//       val client = HttpClient[ByteBuffer](s"http://localhost:$port")
//       val api = client.wire[Api]
//     }

//     Frontend.api.fun(1).failed.map { err =>
//       println("ERR" + err)
//       true mustEqual true
//     }
//   }
// }
