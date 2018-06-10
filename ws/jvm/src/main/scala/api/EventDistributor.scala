package covenant.ws.api

import mycelium.server._
import monix.reactive.subjects.{Subject, PublishSubject}

import java.util.concurrent.ConcurrentHashMap
import concurrent.Future

trait EventDistributor[Event] {
  def subscribe(client: ClientId): Unit
  def unsubscribe(client: ClientId): Unit
  def publish(events: List[Event], origin: Option[ClientId] = None): Unit
}

class HashMapEventDistributor[Event] extends EventDistributor[Event] {
  private val subscribers = new ConcurrentHashMap[ClientId, PublishSubject[List[Event]]]

  def subscribe(client: ClientId): Unit = {
    subscribers.put(client, PublishSubject[List[Event]]())
  }

  def unsubscribe(client: ClientId): Unit = {
    val removed = subscribers.remove(client)
    if (removed != null) removed.onComplete()
  }

  def publish(events: List[Event], origin: Option[ClientId]): Unit = if (events.nonEmpty) {
    scribe.info(s"Event distributor (${subscribers.size} clients): $events from $origin")

    subscribers.forEach { (client, subject) =>
      if (origin.fold(true)(_ != client)) subject.onNext(events)
    }
  }
}
