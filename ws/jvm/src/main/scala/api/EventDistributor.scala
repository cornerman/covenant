package covenant.ws.api

import mycelium.server._

import scala.collection.mutable
import concurrent.Future

trait EventDistributor[Event, State] {
  def subscribe(client: NotifiableClient[Event, State]): Unit
  def unsubscribe(client: NotifiableClient[Event, State]): Unit
  def publish(origin: NotifiableClient[Event, State], events: List[Event]): Unit
}

class HashSetEventDistributor[Event, State] extends EventDistributor[Event, State] {
  val subscribers = mutable.HashSet.empty[NotifiableClient[Event, State]]

  def subscribe(client: NotifiableClient[Event, State]): Unit = {
    subscribers += client
  }

  def unsubscribe(client: NotifiableClient[Event, State]): Unit = {
    subscribers -= client
  }

  def publish(origin: NotifiableClient[Event, State], events: List[Event]): Unit = if (events.nonEmpty) {
    scribe.info(s"Event distributor (${subscribers.size - 1} clients): $events")

    subscribers.foreach { client =>
      if (client != origin) client.notify(_ => Future.successful(events))
    }
  }
}
