package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.transports

import java.nio.charset.Charset

import akka.actor.Actor
import akka.util.ByteString
import com.github.rgafiyatullin.creek_xml.common.HighLevelEvent
import com.github.rgafiyatullin.creek_xml.stream_parser.high_level_parser.{HighLevelParser, HighLevelParserError}
import com.github.rgafiyatullin.creek_xml.stream_parser.low_level_parser.LowLevelParserError
import com.github.rgafiyatullin.creek_xml.stream_parser.tokenizer.TokenizerError
import com.github.rgafiyatullin.creek_xml.stream_writer.high_level_writer.HighLevelWriter
import com.github.rgafiyatullin.creek_xml.utf8.Utf8InputStream
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.XmppStreamActor

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.util.{Failure, Success, Try}

object PlainXml extends XmppTransportFactory {
  override def create: XmppTransport = PlainXml()

  val csUTF8: Charset = Charset.forName("UTF-8")
}

final case class PlainXml(
  writer: HighLevelWriter = HighLevelWriter.empty,
  reader: HighLevelParser = HighLevelParser.empty.withoutPosition,
  utf8InputStream: Utf8InputStream = Utf8InputStream.empty
) extends XmppTransport
{
  override def name: String = "PLAIN-XML"

  override def reset: XmppTransport = PlainXml.create

  override def write(hles: Seq[HighLevelEvent]): (ByteString, PlainXml) = {
    val (strs, writerNext) = hles.foldLeft(writer)(_.in(_)).out

    val builderOut = strs.foldLeft(ByteString.createBuilder) {
      case (builder, str) =>
        val bytes = str.getBytes(PlainXml.csUTF8)
        builder ++= bytes
    }
    (builderOut.result(), copy(writer = writerNext))
  }

  override def read(bytes: ByteString): (Seq[HighLevelEvent], PlainXml) = {
    val (chars, utf8InputStream1) = bytes.foldLeft(Queue.empty[Char], utf8InputStream) {
      case ((acc, utf0), byte) =>
        utf0.in(byte) match {
          case Right(utf1) =>
            val (charOption, utf2) = utf1.out
            (charOption.foldLeft(acc)(_.enqueue(_)), utf2)

          case Left(error) =>
            throw error
        }
    }

    val inputString = chars.mkString

    copy(reader = reader.in(inputString)).fetchHighLevelEvents(Queue.empty)
  }

  override def handover(previousTransport: XmppTransport, xmppStreamActor: XmppStreamActor, next: XmppTransport => Actor.Receive): Actor.Receive =
    next(this)

  @tailrec
  private def fetchHighLevelEvents(acc: Queue[HighLevelEvent]): (Seq[HighLevelEvent], PlainXml) =
    Try[(Option[HighLevelEvent], HighLevelParser)] {
      val (event, reader1) = reader.out
      (Some(event), reader1)
    } recover {
      case HighLevelParserError.LowLevel(
        reader1,
        LowLevelParserError.TokError(
        _, TokenizerError.InputBufferUnderrun(_)))
      =>
        (None, reader1)
    } match {
      case Success((Some(hle), readerNext)) =>
        copy(reader = readerNext).fetchHighLevelEvents(acc.enqueue(hle))

      case Success((None, readerNext)) =>
        (acc, copy(reader = readerNext))

      case Failure(reason) =>
        throw reason
    }
}
