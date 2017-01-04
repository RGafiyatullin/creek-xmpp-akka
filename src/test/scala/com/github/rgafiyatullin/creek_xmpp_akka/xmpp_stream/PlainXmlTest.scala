package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream

import java.nio.charset.Charset

import akka.util.ByteString
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.transports.PlainXml
import org.scalatest.{FlatSpec, Matchers}

class PlainXmlTest extends FlatSpec with Matchers {
  "plainXML" should "handle standard router-server stream-open" in {
    val csUtf8 = Charset.forName("UTF-8")
    val input0 =
      """
        |<?xml version='1.0'?><streams:stream xmlns:streams='http://etherx.jabber.org/streams' xmlns='jabber:client'><streams:features><router xmlns='http://wargaming.net/xmpp/router-service'/></streams:features>
      """.stripMargin

    val input1 =
      """
        |<iq type='result' id='register-ruleset'/>
      """.stripMargin

    val bs0 = ByteString(input0.getBytes(csUtf8))
    val bs1 = ByteString(input1.getBytes(csUtf8))
    val data0 = XmppStreamData(null, PlainXml.create)
    val (_, data1) = data0.withTransport(_.read(bs0))
    val (_, data2) = data1.withTransport(_.read(bs1))
  }
}
