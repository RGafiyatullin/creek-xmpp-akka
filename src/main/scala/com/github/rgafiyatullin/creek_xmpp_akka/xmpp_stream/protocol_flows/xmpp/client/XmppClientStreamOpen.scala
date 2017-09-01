package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.xmpp.client

import akka.util.Timeout
import com.github.rgafiyatullin.creek_xml.common.Attribute
import com.github.rgafiyatullin.creek_xmpp.protocol.XmppConstants
import com.github.rgafiyatullin.creek_xmpp.protocol.stream_error.XmppStreamError
import com.github.rgafiyatullin.creek_xmpp.streams.StreamEvent
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.Protocol
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.xmpp.client.XmppClientProtocol.StreamFeatures
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStream
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStream.api.ResetStreams
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.transports.XmppTransportFactory

import scala.concurrent.{ExecutionContext, Future}

object XmppClientStreamOpen {
  final case class LocalStreamAttributes(attributes: Seq[Attribute]) extends XmppClientProtocol.Artifact
  final case class RemoteStreamAttributes(attributes: Seq[Attribute]) extends XmppClientProtocol.Artifact
}

final case class XmppClientStreamOpen
  (
    attributes: Seq[Attribute] = Seq.empty,
    transportFactoryOption: Option[XmppTransportFactory] = None,
    xmppClientProtocolInternals: XmppClientProtocol.Internals = XmppClientProtocol.Internals.empty)
  (implicit timeout: Timeout)
    extends XmppClientProtocol[XmppClientStreamOpen]
{
  import XmppClientStreamOpen.{LocalStreamAttributes, RemoteStreamAttributes}

  override def withXmppClientProtocolInternals(b: XmppClientProtocol.Internals): XmppClientStreamOpen = copy(xmppClientProtocolInternals = b)

  private def resetStreams: ResetStreams =
    transportFactoryOption.fold(ResetStreams.BeforeWrite())(ResetStreams.BeforeWrite(_))

  override protected def process[In1 <: XmppStream](context: Protocol.Context[In1])(implicit ec: ExecutionContext): Future[Protocol.ProcessResult[In1, XmppStream]] = {
    val xmppStream = context.value
    for {
      _ <- xmppStream.expectConnected()
      _ <- xmppStream.sendStreamEvent(StreamEvent.StreamOpen(attributes), resetStreams)
      StreamEvent.StreamOpen(remoteAttributes) <- xmppStream.receiveStreamEvent()
      StreamEvent.Stanza(firstStanza) <- xmppStream.receiveStreamEvent()
    }
      yield firstStanza.qName match {
        case XmppConstants.names.streams.features =>
          context
            .addArtifact(LocalStreamAttributes(attributes))
            .addArtifact(RemoteStreamAttributes(remoteAttributes))
            .addArtifact(StreamFeatures(firstStanza))
            .complete

        case XmppConstants.names.streams.error =>
          val streamError = XmppStreamError
            .fromStanza(firstStanza)
            .getOrElse(XmppStreamError.InternalServerError().withText("Failed to parse stream-error"))
          context.fail(streamError)

        case unexpectedQName =>
          val streamError = XmppStreamError
            .InvalidXml()
            .withText("Unexpected qname of the first received stanza: %s".format(unexpectedQName))
          context.fail(streamError)
      }

  }
}
