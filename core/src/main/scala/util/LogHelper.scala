package covenant.util

object LogHelper {
  def requestLogLine(path: List[String]): String = logLine(path, None, None)
  def requestLogLine(path: List[String], arguments: Product): String = logLine(path, Some(arguments), None)
  def requestLogLine(path: List[String], arguments: Product, result: Any): String = logLine(path, Some(arguments), Some(result.toString))
  def requestLogLine(path: List[String], arguments: Option[Product], result: Any): String = logLine(path, arguments, Some(result.toString))
  def requestLogLineError(path: List[String], arguments: Product, result: Any): String = logLine(path, Some(arguments), Some(s"Error: $result"))

  private def logLine(path: List[String], arguments: Option[Product], result: Option[String]): String = {
    val pathString = path.mkString(".")
    val argString = "(" + arguments.fold(List.empty[Any])(_.productIterator.toList).mkString(",") + ")"
    val request = pathString + argString
    result.fold(request)(result => s"$request = $result")
  }
}
