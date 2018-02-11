# covenant

Simply create HTTP or Websocket Server and client in scala.

Server-side is JVM only and uses akka, client-side additionally supports scala-js.

## Http

Define a trait as your `Api`:
```scala
trait Api {
    def fun(a: Int): Future[Int]
}
```

### Server

Implement your `Api`:
```scala
object ApiImpl extends Api {
    def fun(a: Int): Future[Int] = Future.successful(a)
}
```

Define a router with [sloth](https://github.com/cornerman/sloth) using e.g. [boopickle](https://github.com/suzaku-io/boopickle) for serialization:
```scala
import sloth._
import boopickle.Default._
import chameleon.ext.boopickle._
import java.nio.ByteBuffer
import cats.implicits._

val router = Router[ByteBuffer, Future].route[Api](ApiImpl)
```

Plug the router into your [akka-http](https://github.com/akka/akka-http) server route:
```
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.RouteResult._
import covenant.http._

Http().bindAndHandle(router.asHttpRoute, interface = "0.0.0.0", port = port)
```

### Client

Let [sloth](https://github.com/cornerman/sloth) implement your `Api` on the client side:
```scala
import sloth._
import boopickle.Default._
import chameleon.ext.boopickle._
import java.nio.ByteBuffer
import cats.implicits._
import covenant.http._

val client = HttpClient[ByteBuffer](yourUrl)
val api: Api = client.wire[Api]
```

Make requests to the server like normal method calls:
```scala
api.fun(1).foreach { num =>
  println(s"Got response: $num")
}
```

## Websocket

TODO: documentation

See:

- [Test](https://github.com/cornerman/covenant/blob/master/ws/jvm/src/test/scala/WsSpec.scala)
- [mycelium](https://github.com/cornerman/mycelium)
