package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows

import akka.util.Timeout
import com.github.rgafiyatullin.creek_xml.dom.Element
import com.github.rgafiyatullin.creek_xmpp.protocol.XmppConstants
import com.github.rgafiyatullin.creek_xmpp.streams.StreamEvent
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.xmpp.{BinaryXmlTransport, SaslAuth, XmppClientProtocol, XmppClientStreamOpen}
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStream
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStream.api.ResetStreams
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.transports.BinaryXml
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.util.XmppServerUtil
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class XmppClientProtocolsTest extends FlatSpec with Matchers with ScalaFutures with XmppServerUtil {
  implicit val defaultPatience: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(500, Millis))

  implicit val timeout: Timeout = 5.seconds
  implicit val executionContext: ExecutionContext = ExecutionContext.global

  val protocolStreamOpenWithUpgradeToBinaryXmlTransport: Protocol[XmppStream, XmppStream] =
    XmppClientStreamOpen(Seq.empty) andThen
      BinaryXmlTransport()

  val protocolStreamOpenWithSaslPlainAuthentication: Protocol[XmppStream, XmppStream] =
    XmppClientStreamOpen(Seq.empty) andThen
      SaslAuth.PlainText("juliet", "something about romeo goes here")

  "stream open with upgrade to binary-xml transport" should "work (happy case)" in
    futureShouldSucceed(
      withClientAndServer { case (xmppServer, clientAndServer) =>
        val streamFeaturesWithoutBinaryXml =
          Element(XmppConstants.names.streams.features, Seq(), Seq())
        val streamFeaturesWithBinaryXml =
          Element(XmppConstants.names.streams.features, Seq(), Seq(
            Element(BinaryXmlTransport.qNameFeature, Seq.empty, Seq.empty)))

        val responseProceed =
          Element(BinaryXmlTransport.qNameProceed, Seq.empty, Seq.empty)

        val client = clientAndServer.client
        val server = clientAndServer.server
        val clientProtocol = protocolStreamOpenWithUpgradeToBinaryXmlTransport
        val clientProtocolResultFuture = clientProtocol.run(Protocol.Context.create(client))

        for {
          _ <- client.expectConnected()
          _ <- server.expectConnected()
          StreamEvent.StreamOpen(_) <- server.receiveStreamEvent()
          _ <- server.sendStreamEvent(StreamEvent.StreamOpen(Seq.empty))
          _ <- server.sendStreamEvent(StreamEvent.Stanza(streamFeaturesWithBinaryXml))
          StreamEvent.Stanza(binaryXmlStartXml) <- server.receiveStreamEvent()
          _ = binaryXmlStartXml.qName should be (BinaryXmlTransport.qNameStart)
          _ <- server.sendStreamEvent(StreamEvent.Stanza(responseProceed), ResetStreams.AfterWrite(BinaryXml))
          _ <- server.sendStreamEvent(StreamEvent.StreamOpen(Seq.empty))
          _ <- server.sendStreamEvent(StreamEvent.Stanza(streamFeaturesWithoutBinaryXml))
          clientProtocolResult <- clientProtocolResultFuture
          _ = clientProtocolResult.isComplete should be (true)
          clientProtocolResultRejected <- BinaryXmlTransport().run(clientProtocolResult.completeContextOption.get)
          _ = clientProtocolResultRejected.isRejected should be (true)
        }
          yield {
            println(clientProtocolResult)
          }
      }
    )

  "stream open with sasl-plain authentication" should "work (happy case)" in
    futureShouldSucceed(withClientAndServer { case (xmppServer, clientAndServer) =>
      val client = clientAndServer.client
      val server = clientAndServer.server



      ???
    })

}
