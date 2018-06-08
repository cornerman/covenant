package covenant.ws.api

import mycelium.server._
import monix.reactive.subjects.{Subject, PublishSubject}

import scala.collection.mutable
import concurrent.Future

trait EventDistributor[Event] {
  def subscribe(client: NotifiableClient[Event]): Unit
  def unsubscribe(client: NotifiableClient[Event]): Unit
  def publish(events: List[Event], origin: Option[NotifiableClient[Event]] = None): Unit
}

class HashSetEventDistributor[Event](notify: NotifiableClient[Event] => List[Event] => Unit) extends EventDistributor[Event] {
  private val subscribers = mutable.HashMap.empty[NotifiableClient[Event], PublishSubject[List[Event]]]

  def subscribe(client: NotifiableClient[Event]): Unit = {
    subscribers += client -> PublishSubject[List[Event]]()
  }

  def unsubscribe(client: NotifiableClient[Event]): Unit = {
    val removed = subscribers.remove(client)
    removed.foreach { _.onComplete() }
  }

  def publish(events: List[Event], origin: Option[NotifiableClient[Event]]): Unit = if (events.nonEmpty) {
    scribe.info(s"Event distributor (${subscribers.size} clients): $events from $origin")

    subscribers.keys.foreach { client =>
      if (origin.fold(true)(_ != client)) notify(client)(events)
    }
  }
}
