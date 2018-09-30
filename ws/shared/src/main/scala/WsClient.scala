package covenant.ws

import sloth._
import mycelium.client._
import mycelium.core.message._
import chameleon._

import monix.reactive.subjects.PublishSubject
import monix.reactive.Observable
import cats.data.EitherT
import cats.implicits._

import scala.concurrent.duration._
import scala.concurrent.{Future, ExecutionContext}

abstract class WsClient[PickleType, Result[_], Event, Failure, ErrorType](
  uri: String,
  connection: WebsocketConnection[PickleType],
  config: WebsocketClientConfig)
(implicit
  serializer: Serializer[ClientMessage[PickleType], PickleType],
  deserializer: Deserializer[ServerMessage[PickleType, Event, Failure], PickleType]
){
  import WsClient._

  private val incidentSubject = PublishSubject[Incident[Event]]()
  protected val mycelium = WebsocketClient[PickleType, Event, Failure](connection, config, defaultHandler(incidentSubject))
  mycelium.run(uri)

  object observable {
    val incident: Observable[Incident[Event]] = incidentSubject
    val event: Observable[List[Event]] = incident.collect { case NewEvents(events) if events.nonEmpty => events }
    val connected: Observable[Connected.type] = incident.collect { case Connected => Connected }
    val closed: Observable[Closed.type] = incident.collect { case Closed => Closed }
  }

  def sendWithDefault = sendWith()

  def sendWith(sendType: SendType = SendType.WhenConnected, requestTimeout: FiniteDuration = 30 seconds): Client[PickleType, Result, ErrorType]
}
object WsClient extends NativeWsClient {

  def fromConnection[PickleType, Event, ErrorType](
    uri: String,
    connection: WebsocketConnection[PickleType],
    config: WebsocketClientConfig,
    logger: LogHandler[Future]
  )(implicit
    ec: ExecutionContext,
    serializer: Serializer[ClientMessage[PickleType], PickleType],
    deserializer: Deserializer[ServerMessage[PickleType, Event, ErrorType], PickleType]
  ) = new WsClient[PickleType, Future, Event, ErrorType, ClientException](uri, connection, config) {

    def sendWith(sendType: SendType, requestTimeout: FiniteDuration) = {
      val transport = new RequestTransport[PickleType, Future] {
        def apply(request: Request[PickleType]): Future[PickleType] = {
          mycelium.send(request.path, request.payload, sendType, requestTimeout).flatMap {
            case Right(res) => Future.successful(res)
            case Left(err) => Future.failed(new Exception(s"Websocket request failed: $err"))
          }
        }
      }

      Client[PickleType, Future, ClientException](transport, logger)
    }
  }

  def fromConnection[PickleType, Event, ErrorType : ClientFailureConvert](
    uri: String,
    connection: WebsocketConnection[PickleType],
    config: WebsocketClientConfig,
    recover: PartialFunction[Throwable, ErrorType],
    logger: LogHandler[EitherT[Future, ErrorType, ?]]
  )(implicit
    ec: ExecutionContext,
    serializer: Serializer[ClientMessage[PickleType], PickleType],
    deserializer: Deserializer[ServerMessage[PickleType, Event, ErrorType], PickleType]
  ) = new WsClient[PickleType, EitherT[Future, ErrorType, ?], Event, ErrorType, ErrorType](uri, connection, config) {

    def sendWith(sendType: SendType, requestTimeout: FiniteDuration) = {
      val transport = new RequestTransport[PickleType, EitherT[Future, ErrorType, ?]] {
        def apply(request: Request[PickleType]): EitherT[Future, ErrorType, PickleType] =
          EitherT(mycelium.send(request.path, request.payload, sendType, requestTimeout).recover(recover andThen Left.apply))
      }

      Client[PickleType, EitherT[Future, ErrorType, ?], ErrorType](transport, logger)
    }
  }

  private def defaultHandler[Event](incidentSubject: PublishSubject[Incident[Event]]) = new IncidentHandler[Event] {
    override def onConnect(): Unit = { incidentSubject.onNext(Connected); () }
    override def onClose(): Unit = { incidentSubject.onNext(Closed); () }
    override def onEvents(events: List[Event]): Unit = {
      incidentSubject.onNext(NewEvents(events))
      ()
    }
  }

  sealed trait Incident[+Event]
  case object Connected extends Incident[Nothing]
  case object Closed extends Incident[Nothing]
  case class NewEvents[+Event](events: List[Event]) extends Incident[Event]
}

