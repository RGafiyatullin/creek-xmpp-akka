package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocols.xmpp.common

import akka.util.Timeout
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocols.{Protocol, ProtocolBase}
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStream
import scala.concurrent.duration._

object XmppProtocol {
  trait Artifact extends Protocol.Artifact

  object Internals {
    def empty: Internals = Internals(5.seconds, Protocol.Internals.empty)
  }
  final case class Internals(
    timeout: Timeout,
    protocolInternals: Protocol.Internals)
  {
    def withProtocolInternals(i: Protocol.Internals): Internals =
      copy(protocolInternals = i)

    def withTimeout(t: Timeout): Internals =
      copy(timeout = t)
  }
}

trait XmppProtocol[Self <: XmppProtocol[Self]] extends Protocol[Self, XmppStream, XmppStream] {
  type Ctx[AnXmppStream <: XmppStream] = Protocol.Context[AnXmppStream]
  type Result[AnXmppStream <: XmppStream] = Protocol.ProcessResult[AnXmppStream, XmppStream]

  def timeout: Timeout = xmppProtocolInternals.timeout
  def withTimeout(t: Timeout): Self = withXmppProtocolInternals(xmppProtocolInternals.withTimeout(t))

  protected implicit lazy val implicitTimeout: Timeout = timeout

  override def protocolInternals: Protocol.Internals = xmppProtocolInternals.protocolInternals
  override def withProtocolInternals(i: Protocol.Internals): Self =
    withXmppProtocolInternals(xmppProtocolInternals.withProtocolInternals(i))

  def xmppProtocolInternals: XmppProtocol.Internals
  def withXmppProtocolInternals(b: XmppProtocol.Internals): Self
}
