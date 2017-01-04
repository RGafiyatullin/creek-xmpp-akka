package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.transports

import akka.actor.Actor
import akka.util.ByteString
import com.github.rgafiyatullin.creek_xml.common.HighLevelEvent
import com.github.rgafiyatullin.creek_xml_binary.encoder.Encoder
import com.github.rgafiyatullin.creek_xml_binary.decoder.Decoder
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.XmppStreamActor

import scala.annotation.tailrec
import scala.collection.immutable.Queue

object BinaryXml extends XmppTransportFactory {
  override def create: XmppTransport = BinaryXml()
}

final case class BinaryXml(
  encoder: Encoder = Encoder.create(1024),
  decoder: Decoder = Decoder.create()
) extends XmppTransport
{
  override def write(hles: Seq[HighLevelEvent]): (ByteString, BinaryXml) = {
    val builderIn = ByteString.createBuilder
    val (builderOut, encoderNext) = hles.foldLeft(builderIn, encoder) {
      case ((bIn, eIn), hle) =>
        val (bytes, eOut) = eIn.encode(hle)
        val bOut = bIn ++= bytes
        (bOut, eOut)
    }
    (builderOut.result(), copy(encoder = encoderNext))
  }

  override def read(bytes: ByteString): (Seq[HighLevelEvent], BinaryXml) =
    copy(decoder = decoder.putBytes(bytes.toArray)).fetchAllHighLevelEvents(Queue.empty)


  override def name = "BINARY-XML"

  override def handover(previousTransport: XmppTransport, xmppStreamActor: XmppStreamActor, next: XmppTransport => Actor.Receive): Actor.Receive =
    next(this)


  @tailrec
  private def fetchAllHighLevelEvents(acc: Queue[HighLevelEvent]): (Seq[HighLevelEvent], BinaryXml) =
    decoder.getHighLevelEvent match {
      case (Some(hle), decoderNext) =>
        copy(decoder = decoderNext).fetchAllHighLevelEvents(acc.enqueue(hle))

      case (None, decoderNext) =>
        (acc, copy(decoder = decoderNext))
    }
}
