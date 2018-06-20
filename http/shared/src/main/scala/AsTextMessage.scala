package covenant.http

import java.nio.ByteBuffer
import java.util.Base64

trait AsTextMessage[T] {
  def write(v: T): String
  def read(v: String): T
}
object AsTextMessage {
  implicit object AsTextMessageString extends AsTextMessage[String] {
    def write(v: String): String = v
    def read(v: String): String = v
  }
  implicit object AsTextMessageByteBuffer extends AsTextMessage[ByteBuffer] {
    def write(v: ByteBuffer): String = {
      val length = v.limit() - v.position()
      val bytes = new Array[Byte](length)
      v.get(bytes, v.position(), length)
      Base64.getEncoder.encodeToString(bytes)
    }
    def read(v: String): ByteBuffer = ByteBuffer.wrap(Base64.getDecoder.decode(v))
  }
}
