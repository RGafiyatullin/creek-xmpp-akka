package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.standard

import com.github.rgafiyatullin.creek_xml.dom.Node
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStream

final case class XmppClientProtocolContext(xmppStream: XmppStream, streamFeatures: Node)
