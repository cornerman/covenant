package covenant.http

import org.scalajs.dom.{Event,UIEvent, FileReader, Blob}
import scala.scalajs.js.|
import scala.scalajs.js.typedarray._, TypedArrayBufferOps._

import java.nio.ByteBuffer
import scala.concurrent.{Promise, Future}

//TODO copied from mycelium
trait JsMessageBuilder[PickleType] {
  import JsMessageBuilder._

  def pack(msg: PickleType): Message
  def unpack(m: Message): Future[Option[PickleType]]
}

object JsMessageBuilder {
  type Message = String | ArrayBuffer | Blob

  implicit val JsMessageBuilderString = new JsMessageBuilder[String] {
    def pack(msg: String): Message = msg
    def unpack(m: Message): Future[Option[String]] = (m: Any) match {
      case s: String => Future.successful(Some(s))
      case b: Blob => readBlob[String, String](_.readAsText(b))(identity)
      case _ => Future.successful(None)
    }
  }
  implicit val JsMessageBuilderByteBuffer = new JsMessageBuilder[ByteBuffer] {
    def pack(msg: ByteBuffer): Message = msg.arrayBuffer.slice(msg.position, msg.limit)
    def unpack(m: Message): Future[Option[ByteBuffer]] = (m: Any) match {
      case a: ArrayBuffer => Future.successful(Option(TypedArrayBuffer.wrap(a)))
      case b: Blob => readBlob[ArrayBuffer, ByteBuffer](_.readAsArrayBuffer(b))(TypedArrayBuffer.wrap(_))
      case _ => Future.successful(None)
    }
  }

  private def readBlob[R,W](doRead: FileReader => Unit)(conv: R => W): Future[Option[W]] = {
    val promise = Promise[Option[W]]()
    val reader = new FileReader
    reader.onload = (_:UIEvent) => {
      val s = reader.result.asInstanceOf[R]
      promise.success(Option(conv(s)))
    }
    reader.onerror = (_:Event) => {
      promise.success(None)
    }
    doRead(reader)
    promise.future
  }
}
