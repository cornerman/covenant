package covenant.ws.api

import mycelium.server._

import scala.collection.mutable

trait EventDistributor[Event] {
  def subscribe(client: NotifiableClient[Event]): Unit
  def unsubscribe(client: NotifiableClient[Event]): Unit
  def publish(origin: NotifiableClient[Event], events: List[Event]): Unit
}

class HashSetEventDistributor[Event] extends EventDistributor[Event] {
  val subscribers = mutable.HashSet.empty[NotifiableClient[Event]]

  def subscribe(client: NotifiableClient[Event]): Unit = {
    subscribers += client
  }

  def unsubscribe(client: NotifiableClient[Event]): Unit = {
    subscribers -= client
  }

  def publish(origin: NotifiableClient[Event], events: List[Event]): Unit = if (events.nonEmpty) {
    scribe.info(s"Event distributor (${subscribers.size - 1} clients): $events")

    subscribers.foreach { client =>
      if (client != origin) client.notify(events)
    }
  }
}
