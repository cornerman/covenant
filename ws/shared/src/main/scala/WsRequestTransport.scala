package covenant.ws

import cats.data.EitherT
import chameleon._
import covenant.RequestOperation
import monix.execution.Scheduler
import monix.reactive.Observable
import mycelium.client._
import mycelium.core.message._
import sloth._

import scala.concurrent.duration._

sealed class WsRequestTransport[PickleType, ErrorType](
  protected val mycelium: WebsocketClient[PickleType, ErrorType]
  )(implicit scheduler: Scheduler) extends RequestTransport[PickleType, EitherT[RequestOperation, ErrorType, ?]] {

  private val defaultTransport = requestWith()
  def apply(request: Request[PickleType]): EitherT[RequestOperation, ErrorType, PickleType] = defaultTransport(request)

  def requestWith(sendType: SendType = SendType.WhenConnected, timeout: Option[FiniteDuration] = Some(30 seconds)) = new RequestTransport[PickleType, EitherT[RequestOperation, ErrorType, ?]] {
    def apply(request: Request[PickleType]): EitherT[RequestOperation, ErrorType, PickleType] = {
      EitherT(RequestOperation { _ =>
        Observable.defer(mycelium.send(request.path, request.payload, sendType, timeout))
      })
    }
  }

  def observable = mycelium.observable
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
