package covenant.ws.api

import mycelium.server._

import scala.collection.mutable
import concurrent.Future

trait EventDistributor[Event, State] {
  def subscribe(client: NotifiableClient[Event, State]): Unit
  def unsubscribe(client: NotifiableClient[Event, State]): Unit
  def publish(events: List[Event], origin: Option[NotifiableClient[Event, State]] = None): Unit
}

class HashSetEventDistributor[Event, State] extends EventDistributor[Event, State] {
  val subscribers = mutable.HashSet.empty[NotifiableClient[Event, State]]

  def subscribe(client: NotifiableClient[Event, State]): Unit = {
    subscribers += client
  }

  def unsubscribe(client: NotifiableClient[Event, State]): Unit = {
    subscribers -= client
  }

  def publish(events: List[Event], origin: Option[NotifiableClient[Event, State]]): Unit = if (events.nonEmpty) {
    scribe.info(s"Event distributor (${subscribers.size} clients): $events from $origin")

    subscribers.foreach { client =>
      if (origin.fold(true)(_ != client)) client.notify(_ => Future.successful(events))
    }
  }
}
