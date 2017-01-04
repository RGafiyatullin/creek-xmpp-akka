package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.transports

import akka.actor.Actor
import akka.util.ByteString
import com.github.rgafiyatullin.creek_xml.common.HighLevelEvent
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.XmppStreamActor

trait XmppTransportFactory {
  def create: XmppTransport
}

trait XmppTransport {
  def write(hles: Seq[HighLevelEvent]): (ByteString, XmppTransport)
  def read(bytes: ByteString): (Seq[HighLevelEvent], XmppTransport)
  def name: String

  def handover(previousTransport: XmppTransport, xmppStreamActor: XmppStreamActor, next: XmppTransport => Actor.Receive): Actor.Receive
}
