package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.xmpp.client

import akka.util.Timeout
import com.github.rgafiyatullin.creek_xml.common.QName
import com.github.rgafiyatullin.creek_xml.dom.Element
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.Protocol
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStream
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.transports.BinaryXml

import scala.concurrent.{ExecutionContext, Future}

object UpgradeToBinaryXmlTransport {
  val xmlNs: String = "http://wargaming.net/xmpp#binary-xml"
  val qNameFeature: QName = QName(xmlNs, "binary-xml")
  val qNameStart: QName = QName(xmlNs, "start")
  val qNameProceed: QName = QName(xmlNs, "proceed")

  case object BinaryXmlTransportUsed extends XmppClientProtocol.Artifact
}

final case class UpgradeToBinaryXmlTransport
  (xmppClientProtocolInternals: XmppClientProtocol.Internals = XmppClientProtocol.Internals.empty)
  (implicit timeout: Timeout)
    extends XmppClientProtocol[UpgradeToBinaryXmlTransport]
{
  import UpgradeToBinaryXmlTransport._

  override def withXmppClientProtocolInternals(b: XmppClientProtocol.Internals): UpgradeToBinaryXmlTransport = copy(xmppClientProtocolInternals = b)

  private def requestStart: Element = Element(qNameStart, Seq(), Seq())

  private def hasFeature(context: Protocol.Context[XmppStream]): Boolean =
    features.feature(context, qNameFeature).isDefined

  private def ifHasFeature[In1 <: XmppStream]
    (context: Protocol.Context[In1])
    (doIt: => Future[Protocol.ProcessResult[In1, XmppStream]])
  : Future[Protocol.ProcessResult[In1, XmppStream]] =
    if (!hasFeature(context))
      Future.successful(context.reject())
    else
      doIt

  override protected def process[In1 <: XmppStream]
    (context: Protocol.Context[In1])
    (implicit ec: ExecutionContext)
  : Future[Protocol.ProcessResult[In1, XmppStream]] = {
    val xmppStream = context.value

    ifHasFeature(context)(
      negotiation.run(context, requestStart) {
        case `qNameProceed` =>
          (_) =>
            streams.reopen(context.addArtifact(BinaryXmlTransportUsed), Some(BinaryXml))
      })
  }
}
