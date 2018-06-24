package covenant

object TransportException {
  case class RequestError(msg: String) extends Exception(msg)
  case object StoppedDownstream extends Exception
}
