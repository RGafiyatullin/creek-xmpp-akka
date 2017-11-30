package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocols.xmpp.server

import com.github.rgafiyatullin.creek_xml.common.{Attribute, QName}
import com.github.rgafiyatullin.creek_xml.dom.{Element, Node}
import com.github.rgafiyatullin.creek_xmpp.protocol.XmppConstants
import com.github.rgafiyatullin.creek_xmpp.streams.StreamEvent
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocols.Protocol
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStream
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStream.api.ResetStreams
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.transports.XmppTransportFactory

import scala.concurrent.{ExecutionContext, Future}

object XmppServerStreamOpen {
  final case class LocalStreamAttributes(attributes: Seq[Attribute]) extends XmppServerProtocol.Artifact
  final case class RemoteStreamAttributes(attributes: Seq[Attribute]) extends XmppServerProtocol.Artifact

  def apply(): XmppServerStreamOpen =
    apply(Seq.empty)

  def apply(attributes: Seq[Attribute]): XmppServerStreamOpen =
    XmppServerStreamOpen(attributes, xmppServerProtocolInternals = XmppServerProtocol.Internals.empty)
}

final case class XmppServerStreamOpen private(
  attributes: Seq[Attribute],
  xmppServerProtocolInternals: XmppServerProtocol.Internals)
    extends XmppServerProtocol[XmppServerStreamOpen]
{
  import XmppServerStreamOpen._

  override def withXmppServerProtocolInternals(i: XmppServerProtocol.Internals): XmppServerStreamOpen =
    copy(xmppServerProtocolInternals = i)

  private def streamFeaturesStanza: Node =
    Element(XmppConstants.names.streams.features, Seq.empty, Seq.empty)

  override protected def process[In1 <: XmppStream]
    (context: Protocol.Context[In1])
    (implicit ec: ExecutionContext)
  : Future[Protocol.ProcessResult[In1, XmppStream]] = {
    val xmppStream = context.value
    for {
      StreamEvent.StreamOpen(clientStreamOpenAttrs) <- xmppStream.receiveStreamEvent()
      _ <- xmppStream.sendStreamEvent(StreamEvent.StreamOpen(attributes))
      _ <- xmppStream.sendStreamEvent(StreamEvent.Stanza(streamFeaturesStanza))
    }
      yield context
          .addArtifact(RemoteStreamAttributes(clientStreamOpenAttrs))
          .addArtifact(LocalStreamAttributes(attributes))
          .complete
  }
}
