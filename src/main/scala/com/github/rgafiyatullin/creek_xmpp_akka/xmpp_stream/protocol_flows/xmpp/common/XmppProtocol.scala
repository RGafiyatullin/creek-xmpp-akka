package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.xmpp.common

import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.{ProtocolBase, Protocol}
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStream

object XmppProtocol {
  object Internals {
    def empty: Internals = Internals(ProtocolBase.Internals.empty)
  }
  final case class Internals(protocolInternals: ProtocolBase.Internals) {
    def withProtocolInternals(i: ProtocolBase.Internals): Internals =
      copy(protocolInternals = i)
  }
}

trait XmppProtocol[Self <: XmppProtocol[Self]] extends Protocol[Self, XmppStream, XmppStream] {
  type Ctx[AnXmppStream <: XmppStream] = ProtocolBase.Context[AnXmppStream]

  override def protocolInternals: ProtocolBase.Internals = xmppProtocolInternals.protocolInternals
  override def withProtocolInternals(i: ProtocolBase.Internals): Self =
    withXmppProtocolInternals(xmppProtocolInternals.withProtocolInternals(i))

  def xmppProtocolInternals: XmppProtocol.Internals
  def withXmppProtocolInternals(b: XmppProtocol.Internals): Self
}
