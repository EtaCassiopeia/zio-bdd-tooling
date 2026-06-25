package zio.bdd.lsp.bsp

import zio.*

import java.io.{InputStream, OutputStream}
import java.nio.charset.StandardCharsets

// Content-Length-framed JSON-RPC transport.
// BSP uses the same wire format as LSP:
//   Content-Length: <n>\r\n
//   \r\n
//   <n bytes of UTF-8 JSON>
private[bsp] object BspJsonRpc:

  private val Utf8 = StandardCharsets.UTF_8

  def send(out: OutputStream, body: String): UIO[Unit] =
    ZIO.succeedBlocking {
      val bytes  = body.getBytes(Utf8)
      val header = s"Content-Length: ${bytes.length}\r\n\r\n".getBytes(Utf8)
      out.synchronized {
        out.write(header)
        out.write(bytes)
        out.flush()
      }
    }

  // Reads one complete message from `in`. Returns None on EOF.
  // Blocks the calling thread (must be run on a blocking thread pool).
  def receive(in: InputStream): Task[Option[String]] =
    ZIO.attemptBlocking {
      readHeader(in).flatMap { header =>
        val contentLength = header.linesIterator
          .find(_.startsWith("Content-Length:"))
          .map(_.drop("Content-Length:".length).trim.toInt)
        contentLength.map { len =>
          val body   = new Array[Byte](len)
          var offset = 0
          while offset < len do
            val n = in.read(body, offset, len - offset)
            if n == -1 then offset = len // EOF mid-body — truncated
            else offset += n
          new String(body, Utf8)
        }
      }
    }

  // Read bytes until the \r\n\r\n header terminator is seen.
  // Returns None on EOF before any header bytes are received.
  private def readHeader(in: InputStream): Option[String] =
    // Sliding 4-byte window to detect \r\n\r\n (13,10,13,10)
    var b0, b1, b2, b3 = 0
    val buf            = new java.io.ByteArrayOutputStream(256)
    var b              = in.read()
    if b == -1 then return None
    var found = false
    while !found && b != -1 do
      buf.write(b)
      b0 = b1; b1 = b2; b2 = b3; b3 = b
      if b0 == 13 && b1 == 10 && b2 == 13 && b3 == 10 then found = true
      else b = in.read()
    if found then Some(buf.toString(Utf8)) else None
