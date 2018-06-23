package covenant

object TransportException {
  case class TransportException(msg: String) extends Exception(s"Error in RequestTransport: $msg")
}
