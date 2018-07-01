package covenant.ws

import chameleon._
import covenant.RequestOperation
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import mycelium.client._
import mycelium.core.message._
import sloth._

import scala.concurrent.duration._

sealed class WsRequestTransport[PickleType, ErrorType](
  protected val mycelium: WebsocketClient[PickleType, ErrorType]
  )(implicit scheduler: Scheduler) extends RequestTransport[PickleType, RequestOperation[ErrorType, ?]] {

  private val defaultTransport = requestWith()
  def apply(request: Request[PickleType]): RequestOperation[ErrorType, PickleType] = defaultTransport(request)

  def requestWith(sendType: SendType = SendType.WhenConnected, timeout: Option[FiniteDuration] = Some(30 seconds)) = new RequestTransport[PickleType, RequestOperation[ErrorType, ?]] {
    def apply(request: Request[PickleType]): RequestOperation[ErrorType, PickleType] = {
      val responseStream = mycelium.send(request.path, request.payload, sendType, timeout)
      RequestOperation(
        responseStream.flatMap {
          case Right(o) => o.lastL.map(Right.apply)
          case Left(err) => Task.pure(Left(err))
        },
        Observable.fromTask(responseStream).flatMap {
          case Right(o) => o.map(Right.apply)
          case Left(err) => Observable.pure(Left(err))
        })
    }
  }

  def connected = mycelium.connected
}
object WsRequestTransport {
  def fromConnection[PickleType, ErrorType](
    uri: String,
    connection: WebsocketConnection[PickleType],
    config: WebsocketClientConfig = WebsocketClientConfig()
  )(implicit
    scheduler: Scheduler,
    serializer: Serializer[ClientMessage[PickleType], PickleType],
    deserializer: Deserializer[ServerMessage[PickleType, ErrorType], PickleType]
  ) = {
    val mycelium = WebsocketClient[PickleType, ErrorType](connection, config)
    mycelium.run(uri)

    new WsRequestTransport[PickleType, ErrorType](mycelium)
  }
}
