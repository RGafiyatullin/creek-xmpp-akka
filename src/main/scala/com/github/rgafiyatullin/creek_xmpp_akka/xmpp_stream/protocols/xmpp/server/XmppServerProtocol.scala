package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocols.xmpp.server

import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocols.xmpp.common.XmppProtocol

object XmppServerProtocol {
  trait Artifact extends XmppProtocol.Artifact

  object Internals {
    def empty: Internals = Internals(XmppProtocol.Internals.empty)
  }
  final case class Internals(xmppProtocolInternals: XmppProtocol.Internals) {
    def withXmppProtocolInternals(i: XmppProtocol.Internals): Internals =
      copy(xmppProtocolInternals = i)
  }
}

trait XmppServerProtocol[Self <: XmppServerProtocol[Self]] extends XmppProtocol[Self] {
  def xmppServerProtocolInternals: XmppServerProtocol.Internals
  def withXmppServerProtocolInternals(i: XmppServerProtocol.Internals): Self

  override def xmppProtocolInternals: XmppProtocol.Internals =
    xmppServerProtocolInternals.xmppProtocolInternals

  override def withXmppProtocolInternals(i: XmppProtocol.Internals): Self =
    withXmppServerProtocolInternals(xmppServerProtocolInternals.withXmppProtocolInternals(i))
}
