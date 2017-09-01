package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.xmpp.common

import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.Protocol
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStream

object XmppProtocol {
  object Internals {
    def empty: Internals = Internals(Protocol.Internals.empty)
  }
  final case class Internals(protocolInternals: Protocol.Internals) {
    def withProtocolInternals(i: Protocol.Internals): Internals =
      copy(protocolInternals = i)
  }
}

trait XmppProtocol extends Protocol[XmppStream, XmppStream] {
  type Ctx[AnXmppStream <: XmppStream] = Protocol.Context[AnXmppStream]

  override def protocolInternals: Protocol.Internals = xmppProtocolInternals.protocolInternals
  override def withProtocolInternals(i: Protocol.Internals): Self =
    withXmppProtocolInternals(xmppProtocolInternals.withProtocolInternals(i))

  def xmppProtocolInternals: XmppProtocol.Internals
  def withXmppProtocolInternals(b: XmppProtocol.Internals): Self
}
