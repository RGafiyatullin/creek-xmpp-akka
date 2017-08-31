package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.xmpp

import akka.util.Timeout
import com.github.rgafiyatullin.creek_xml.common.QName
import com.github.rgafiyatullin.creek_xml.dom.{Element, Node}
import com.github.rgafiyatullin.creek_xmpp.protocol.XmppConstants
import com.github.rgafiyatullin.creek_xmpp.protocol.stream_error.XmppStreamError
import com.github.rgafiyatullin.creek_xmpp.streams.StreamEvent
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.Protocol
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.Protocol.ProcessResult
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStream
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.transports.BinaryXml

import scala.concurrent.{ExecutionContext, Future}

object BinaryXmlTransport {
  val xmlNs: String = "http://wargaming.net/xmpp#binary-xml"
  val qNameFeature: QName = QName(xmlNs, "binary-xml")
  val qNameStart: QName = QName(xmlNs, "start")
  val qNameProceed: QName = QName(xmlNs, "proceed")
}

final case class BinaryXmlTransport()(implicit timeout: Timeout) extends XmppClientProtocol {
  import BinaryXmlTransport._

  private def requestStart: Element = Element(qNameStart, Seq(), Seq())

  private def hasFeature(context: Protocol.Context[XmppStream]): Boolean =
    features.feature(context, qNameFeature).isDefined

  private def ifHasFeature[In1 <: XmppStream]
    (context: Protocol.Context[In1])
    (doIt: => Future[ProcessResult[In1, XmppStream]])
  : Future[ProcessResult[In1, XmppStream]] =
    if (!hasFeature(context))
      Future.successful(context.reject())
    else
      doIt

  override protected def process[In1 <: XmppStream]
    (context: Protocol.Context[In1])
    (implicit ec: ExecutionContext)
  : Future[ProcessResult[In1, XmppStream]] = {
    val xmppStream = context.value

    ifHasFeature(context)(
      negotiation.run(context, requestStart) {
        case `qNameProceed` =>
          (_) => XmppClientStreamOpen(Seq.empty, Some(BinaryXml)).run(context)
      })
  }
}
