package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream

import java.nio.charset.Charset

import akka.actor.Actor
import akka.util.ByteString
import com.github.rgafiyatullin.creek_xml.common.{Attribute, HighLevelEvent}
import com.github.rgafiyatullin.creek_xmpp.streams.StreamEvent
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.transports.PlainXml
import org.scalatest.{FlatSpec, Matchers}

class PlainXmlTest extends FlatSpec with Matchers {
  val csUtf8: Charset = Charset.forName("UTF-8")

  "plainXML" should "handle standard router-server stream-open" in {
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
    val data0 = XmppStreamData(Actor.noSender, PlainXml.create)
    val (_, data1) = data0.withTransport(_.read(bs0))
    val (_, data2) = data1.withTransport(_.read(bs1))
  }

  it should "succesfully parse stream-open and auth from Adium" in {
    val bs0 = ByteString("""<?xml version='1.0' ?><stream:stream to='c2s.3pp-01.xmppcs.dev' xmlns='jabber:client' xmlns:stream='http://etherx.jabber.org/streams' version='1.0' xml:lang='en'>
                           |<auth
                           |	xmlns='urn:ietf:params:xml:ns:xmpp-sasl'
                           |	mechanism='PLAIN'
                           |	xmlns:ga='http://www.google.com/talk/protocol/auth'
                           |	ga:client-uses-full-bind-result='true'>ADIANTQ5OTY4MDQ0Nzc1NTMzOTA5</auth>
                         """.stripMargin.getBytes(csUtf8))
    val data0 = XmppStreamData(Actor.noSender, PlainXml.create)
    val (hles, data1) = data0.withTransport(_.read(bs0))
    hles.size should be (6)
    hles(0) shouldBe a[HighLevelEvent.ProcessingInstrutcion]
    hles(1) shouldBe a[HighLevelEvent.ElementOpen]
    hles(1).asInstanceOf[HighLevelEvent.ElementOpen].attributes.toSet should be (Set(
      Attribute.Unprefixed("to", "c2s.3pp-01.xmppcs.dev"),
      Attribute.NsImport("", "jabber:client"),
      Attribute.NsImport("stream", "http://etherx.jabber.org/streams"),
      Attribute.Unprefixed("version", "1.0"),
      Attribute.Prefixed("xml", "lang", "en")
    ))
    hles(2) shouldBe a[HighLevelEvent.Whitespace]
    hles(3) shouldBe a[HighLevelEvent.ElementOpen]
    hles(3).asInstanceOf[HighLevelEvent.ElementOpen].attributes.toSet should be (Set(
      Attribute.NsImport("", "urn:ietf:params:xml:ns:xmpp-sasl"),
      Attribute.Unprefixed("mechanism", "PLAIN"),
      Attribute.NsImport("ga", "http://www.google.com/talk/protocol/auth"),
      Attribute.Prefixed("ga", "client-uses-full-bind-result", "true")
    ))
    hles(4) shouldBe a[HighLevelEvent.PCData]
    hles(4).asInstanceOf[HighLevelEvent.PCData].text should be ("ADIANTQ5OTY4MDQ0Nzc1NTMzOTA5")
    hles(5) shouldBe a[HighLevelEvent.ElementClose]

    val (streamEvents, data2) = data0.withInputStream(hles.foldLeft(_)(_.in(_)).outAll)

    streamEvents(0) shouldBe a[StreamEvent.StreamOpen]
    streamEvents(0).asInstanceOf[StreamEvent.StreamOpen].attributes should contain (Attribute.Unprefixed("to", "c2s.3pp-01.xmppcs.dev"))
    streamEvents(0).asInstanceOf[StreamEvent.StreamOpen].attributes should contain (Attribute.Unprefixed("version", "1.0"))
    streamEvents(1) shouldBe a[StreamEvent.Stanza]
    streamEvents(1).asInstanceOf[StreamEvent.Stanza].element.attributes should contain (Attribute.Unprefixed("mechanism", "PLAIN"))
  }

}
