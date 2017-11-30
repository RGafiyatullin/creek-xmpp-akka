package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocols.xmpp.client

import com.github.rgafiyatullin.creek_xml.common.QName
import com.github.rgafiyatullin.creek_xml.dom.Node
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocols.Protocol
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStream

import scala.concurrent.{ExecutionContext, Future}

object RequireStreamFeature {
  def apply(feature: QName, filter: Node => Boolean): RequireStreamFeature =
    RequireStreamFeature(feature, filter, xmppClientProtocolInternals = XmppClientProtocol.Internals.empty)

  def apply(feature: QName): RequireStreamFeature =
    apply(feature, _ => true)
}

final case class RequireStreamFeature private (
  feature: QName,
  filter: Node => Boolean,
  xmppClientProtocolInternals: XmppClientProtocol.Internals)
    extends XmppClientProtocol[RequireStreamFeature]
{
  override def withXmppClientProtocolInternals(b: XmppClientProtocol.Internals): RequireStreamFeature =
    copy(xmppClientProtocolInternals = b)

  override protected def process[In1 <: XmppStream]
  (context: Protocol.Context[In1])
  (implicit ec: ExecutionContext)
  : Future[Protocol.ProcessResult[In1, XmppStream]] =
    Future.successful {
      if (features.feature(context, feature).exists(filter))
        context.complete
      else
        context.reject
    }

}
