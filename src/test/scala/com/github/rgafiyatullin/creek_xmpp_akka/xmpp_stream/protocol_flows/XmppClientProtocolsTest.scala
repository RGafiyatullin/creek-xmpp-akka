package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows

import java.net.InetSocketAddress

import akka.util.Timeout
import com.github.rgafiyatullin.creek_xml.common.Attribute
import com.github.rgafiyatullin.creek_xml.dom.{CData, Element}
import com.github.rgafiyatullin.creek_xmpp.protocol.XmppConstants
import com.github.rgafiyatullin.creek_xmpp.protocol.stream_error.XmppStreamError
import com.github.rgafiyatullin.creek_xmpp.streams.StreamEvent
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.xmpp.client.{SaslAuth, UpgradeToBinaryXmlTransport, XmppClientProtocol, XmppClientStreamOpen}
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStream
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStream.api.ResetStreams
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.transports.{BinaryXml, PlainXml}
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

  val protocolStreamOpenWithUpgradeToBinaryXmlTransport: ProtocolBase[XmppStream, XmppStream] =
    XmppClientStreamOpen(Seq.empty) andThen
      UpgradeToBinaryXmlTransport()

  val protocolStreamOpenWithSaslPlainAuthentication: ProtocolBase[XmppStream, XmppStream] =
    XmppClientStreamOpen(Seq.empty) andThen
      SaslAuth.PlainText().withUsername("juliet").withPassword("something about romeo goes here")

  val protocolStreamOpenWithOptionalBinaryXmlTransportAndSaslPlainAuthentication: ProtocolBase[XmppStream, XmppStream] =
    XmppClientStreamOpen(Seq.empty) andThen
      (UpgradeToBinaryXmlTransport() orElse XmppClientProtocol.AlwaysComplete()) andThen
      (SaslAuth.PlainText().withUsername("romeo").withPassword("something about juliet goes here") orElse
        XmppClientProtocol.FailWithStreamError(XmppStreamError.UnsupportedFeature().withText("No suitable auth-mechanism available")))


  "stream open with upgrade to binary-xml transport" should "work (happy case)" in
    futureShouldSucceed(
      withClientAndServer { case (xmppServer, clientAndServer) =>
        val streamFeaturesWithoutBinaryXml =
          Element(XmppConstants.names.streams.features, Seq(), Seq())
        val streamFeaturesWithBinaryXml =
          Element(XmppConstants.names.streams.features, Seq(), Seq(
            Element(UpgradeToBinaryXmlTransport.qNameFeature, Seq.empty, Seq.empty)))

        val responseProceed =
          Element(UpgradeToBinaryXmlTransport.qNameProceed, Seq.empty, Seq.empty)

        val client = clientAndServer.client
        val server = clientAndServer.server
        val clientProtocol = protocolStreamOpenWithUpgradeToBinaryXmlTransport
        val clientProtocolResultFuture = clientProtocol.run(Protocol.Context.create(client))

        for {
          _ <- server.expectConnected()
          StreamEvent.StreamOpen(_) <- server.receiveStreamEvent()
          _ <- server.sendStreamEvent(StreamEvent.StreamOpen(Seq.empty))
          _ <- server.sendStreamEvent(StreamEvent.Stanza(streamFeaturesWithBinaryXml))
          StreamEvent.Stanza(binaryXmlStartXml) <- server.receiveStreamEvent()
          _ = binaryXmlStartXml.qName should be (UpgradeToBinaryXmlTransport.qNameStart)
          _ <- server.sendStreamEvent(StreamEvent.Stanza(responseProceed), ResetStreams.AfterWrite(BinaryXml))
          _ <- server.sendStreamEvent(StreamEvent.StreamOpen(Seq.empty))
          _ <- server.sendStreamEvent(StreamEvent.Stanza(streamFeaturesWithoutBinaryXml))
          clientProtocolResult <- clientProtocolResultFuture
          _ = println(clientProtocolResult)
          _ = clientProtocolResult.isComplete should be (true)
          clientProtocolResultRejected <- UpgradeToBinaryXmlTransport().run(clientProtocolResult.completeContextOption.get)
          _ = clientProtocolResultRejected.isRejected should be (true)
        }
          yield println(clientProtocolResult)
      })

  "stream open with sasl-plain authentication" should "work (happy case)" in
    futureShouldSucceed(withClientAndServer { case (xmppServer, clientAndServer) =>
      val streamFeaturesWithoutSaslAuth =
        Element(XmppConstants.names.streams.features, Seq(), Seq())
      val streamFeaturesWithSaslAuth =
        Element(XmppConstants.names.streams.features, Seq(), Seq(
          Element(SaslAuth.names.qNameSaslMechanismsFeature, Seq.empty, Seq(
            Element(SaslAuth.names.qNameSaslMechanism, Seq(), Seq(
              CData("PLAIN")))))))

      val client = clientAndServer.client
      val server = clientAndServer.server

      val clientProtocol = protocolStreamOpenWithSaslPlainAuthentication
      val clientProtocolResultFuture = clientProtocol.run(Protocol.Context.create(client))

      val saslAuthSuccess = Element(SaslAuth.names.qNameSuccess, Seq.empty, Seq.empty)

      for {
        _ <- server.expectConnected()
        StreamEvent.StreamOpen(_) <- server.receiveStreamEvent()
        _ <- server.sendStreamEvent(StreamEvent.StreamOpen(Seq.empty))
        _ <- server.sendStreamEvent(StreamEvent.Stanza(streamFeaturesWithSaslAuth))
        StreamEvent.Stanza(saslAuthRequest) <- server.receiveStreamEvent()
        _ = saslAuthRequest.qName should be (SaslAuth.names.qNameAuth)
        _ <- server.sendStreamEvent(StreamEvent.Stanza(saslAuthSuccess), ResetStreams.AfterWrite())
        StreamEvent.StreamOpen(_) <- server.receiveStreamEvent()
        _ <- server.sendStreamEvent(StreamEvent.StreamOpen(Seq.empty))
        _ <- server.sendStreamEvent(StreamEvent.Stanza(streamFeaturesWithoutSaslAuth))
        clientProtocolResult <- clientProtocolResultFuture
        _ = clientProtocolResult.isComplete should be (true)
        rejected <- SaslAuth.PlainText().run(clientProtocolResult.completeContextOption.get)
        _ = rejected.isRejected should be (true)
      }
        yield println(clientProtocolResult)
    })

  "stream open with optional binary-xml transport and mandatory sasl-plain authentication" should "work (happy case with binary-xml)" in
    futureShouldSucceed(withClientAndServer { case (xmppServer, clientAndServer) =>
      val streamFeatures0 =
        Element(XmppConstants.names.streams.features, Seq(), Seq(
          Element(SaslAuth.names.qNameSaslMechanismsFeature, Seq.empty, Seq(
            Element(SaslAuth.names.qNameSaslMechanism, Seq(), Seq(
              CData("PLAIN"))))),
          Element(UpgradeToBinaryXmlTransport.qNameFeature, Seq.empty, Seq.empty)))

      val streamFeatures1 =
        Element(XmppConstants.names.streams.features, Seq(), Seq(
          Element(SaslAuth.names.qNameSaslMechanismsFeature, Seq.empty, Seq(
            Element(SaslAuth.names.qNameSaslMechanism, Seq(), Seq(
              CData("PLAIN")))))))

      val streamFeatures2 =
        Element(XmppConstants.names.streams.features, Seq(), Seq())

      val client = clientAndServer.client
      val server = clientAndServer.server

      val clientProtocol = protocolStreamOpenWithOptionalBinaryXmlTransportAndSaslPlainAuthentication
      val clientProtocolResultFuture = clientProtocol.run(Protocol.Context.create(client))

      val binaryXmlProceed =
        Element(UpgradeToBinaryXmlTransport.qNameProceed, Seq.empty, Seq.empty)
      val saslAuthSuccess =
        Element(SaslAuth.names.qNameSuccess, Seq.empty, Seq.empty)



      for {
        _ <- server.expectConnected()
        StreamEvent.StreamOpen(_) <- server.receiveStreamEvent()
        _ <- server.sendStreamEvent(StreamEvent.StreamOpen(Seq.empty))
        _ <- server.sendStreamEvent(StreamEvent.Stanza(streamFeatures0))
        StreamEvent.Stanza(binaryXmlStart) <- server.receiveStreamEvent()
        _ = binaryXmlStart.qName should be (UpgradeToBinaryXmlTransport.qNameStart)
        _ <- server.sendStreamEvent(StreamEvent.Stanza(binaryXmlProceed), ResetStreams.AfterWrite(BinaryXml))
        StreamEvent.StreamOpen(_) <- server.receiveStreamEvent()
        _ <- server.sendStreamEvent(StreamEvent.StreamOpen(Seq.empty))
        _ <- server.sendStreamEvent(StreamEvent.Stanza(streamFeatures1))
        StreamEvent.Stanza(saslAuthRequest) <- server.receiveStreamEvent()
        _ = saslAuthRequest.qName should be (SaslAuth.names.qNameAuth)
        _ <- server.sendStreamEvent(StreamEvent.Stanza(saslAuthSuccess), ResetStreams.AfterWrite())
        StreamEvent.StreamOpen(_) <- server.receiveStreamEvent()
        _ <- server.sendStreamEvent(StreamEvent.StreamOpen(Seq.empty))
        _ <- server.sendStreamEvent(StreamEvent.Stanza(streamFeatures2))

        clientProtocolResult <- clientProtocolResultFuture
      }
        yield println(clientProtocolResult)
    })

  it should "work (happy case w/o binary-xml transport)" in
    futureShouldSucceed(withClientAndServer { case (xmppServer, clientAndServer) =>
      val streamFeatures1 =
        Element(XmppConstants.names.streams.features, Seq(), Seq(
          Element(SaslAuth.names.qNameSaslMechanismsFeature, Seq.empty, Seq(
            Element(SaslAuth.names.qNameSaslMechanism, Seq(), Seq(
              CData("PLAIN")))))))

      val streamFeatures2 =
        Element(XmppConstants.names.streams.features, Seq(), Seq())

      val client = clientAndServer.client
      val server = clientAndServer.server

      val clientProtocol = protocolStreamOpenWithOptionalBinaryXmlTransportAndSaslPlainAuthentication
      val clientProtocolResultFuture = clientProtocol.run(Protocol.Context.create(client))

      val binaryXmlProceed =
        Element(UpgradeToBinaryXmlTransport.qNameProceed, Seq.empty, Seq.empty)
      val saslAuthSuccess =
        Element(SaslAuth.names.qNameSuccess, Seq.empty, Seq.empty)



      for {
        _ <- server.expectConnected()
        StreamEvent.StreamOpen(_) <- server.receiveStreamEvent()
        _ <- server.sendStreamEvent(StreamEvent.StreamOpen(Seq.empty))
        _ <- server.sendStreamEvent(StreamEvent.Stanza(streamFeatures1))
        StreamEvent.Stanza(saslAuthRequest) <- server.receiveStreamEvent()
        _ = saslAuthRequest.qName should be (SaslAuth.names.qNameAuth)
        _ <- server.sendStreamEvent(StreamEvent.Stanza(saslAuthSuccess), ResetStreams.AfterWrite())
        StreamEvent.StreamOpen(_) <- server.receiveStreamEvent()
        _ <- server.sendStreamEvent(StreamEvent.StreamOpen(Seq.empty))
        _ <- server.sendStreamEvent(StreamEvent.Stanza(streamFeatures2))

        clientProtocolResult <- clientProtocolResultFuture
      }
        yield println(clientProtocolResult)
    })


//  "a mere test" should "do" in
//    futureShouldSucceed(withActorSystem() { actorSystem =>
//      val client = XmppStream(actorSystem.actorOf(XmppStream.propsConnectTo(new InetSocketAddress("192.168.99.100", 5222), PlainXml)))
//      val clientInitProtocol =
//        XmppClientStreamOpen(Seq(Attribute.Unprefixed("to", "c2s.3pp-01.xmppcs.dev"))) andThen
//          (UpgradeToBinaryXmlTransport() orElse XmppClientProtocol.AlwaysComplete()) andThen
//          SaslAuth.PlainText().withUsername("3").withPassword("3413832967349465905")
//
//      for {
//        _ <- client.expectConnected()
//        result <- clientInitProtocol.run(Protocol.Context.create(client))
//      }
//        yield println(result)
//    })
}
