package covenant

object TransportException {
  case class RequestError(msg: String) extends Exception(msg)
  case class UnexpectedResult(msg: String) extends Exception(msg)
}
