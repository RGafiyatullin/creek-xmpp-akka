package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream

import java.net.InetSocketAddress
import java.util.UUID

import akka.actor.{Actor, ActorSystem, Props, Status, SupervisorStrategy}
import akka.event.Logging.LogLevel
import akka.io.{IO, Tcp}
import com.github.rgafiyatullin.creek_xml.common.QName
import com.github.rgafiyatullin.creek_xml.dom.Element
import com.github.rgafiyatullin.creek_xmpp.protocol.stanzas.streams.Features
import com.github.rgafiyatullin.creek_xmpp.streams.StreamEvent
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStream
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStream.api.ResetStreams
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.transports.{BinaryXml, PlainXml}
import com.github.rgafiyatullin.owl_akka_goodies.actor_future.{ActorFuture, ActorStdReceive}
import org.scalatest.FlatSpec
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class XmppStream3Test extends FlatSpec with ScalaFutures {

  org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.DEBUG)

  implicit val defaultPatience =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(500, Millis))

  "it" should "work" in {
    val actorSystem = ActorSystem("XmppStream3Test")
    actorSystem.eventStream.setLogLevel(LogLevel(4))
    implicit val executionContext = actorSystem.dispatcher
    val log = actorSystem.log



    sealed trait Exec {
      def run(client: XmppStream, server: XmppStream): Future[Any]
    }
    final case class ExecImpl[Ret](f: (XmppStream, XmppStream) => Future[Ret]) extends Exec {
      override def run(client: XmppStream, server: XmppStream) = f(client, server).mapTo[Any]
    }

    class Sup(done: Promise[Any], task: (XmppStream, XmppStream) => Future[Any]) extends Actor with ActorFuture with ActorStdReceive {
      override def receive = initialize()


      override def supervisorStrategy =
        SupervisorStrategy.stoppingStrategy

      def whenBinding(): Receive = {
        case Tcp.Bound(localAddress) =>
          log.info("Bound at {}", localAddress)
          val connectTo = new InetSocketAddress("127.0.0.1", localAddress.getPort)
          val clientActorRef = context.actorOf(XmppStream.propsConnectTo(connectTo, PlainXml, Some(3.seconds)), "client")
          val client = XmppStream(clientActorRef)
          context become whenAccepting(client)
      }


      def whenAccepting(client: XmppStream): Receive = {
        case Tcp.Connected(remoteAddress, localAddress) =>
          val connection = sender()
          log.info("Accepted {} -> {} [conn: {}]", remoteAddress, localAddress, connection)
          val serverActorRef = context.actorOf(XmppStream.propsFromConnection(connection, PlainXml), "server")
          val server = XmppStream(serverActorRef)
          log.info("About to become whenAccepted")

          task(client, server).onComplete(done.complete)

          context become stdReceive.discard
      }

      def initialize(): Receive = {
        IO(Tcp)(context.system) ! Tcp.Bind(self, new InetSocketAddress(0))
        whenBinding()
      }
    }

    val jobDone = Promise[Any]()

    val supActorRef = actorSystem actorOf Props(new Sup(jobDone, {
      case (client: XmppStream, server: XmppStream) =>
        log.info("Client: {}", client)
        log.info("Server: {}", server)

        val stanzaBinaryStart = Element(QName("binary-xml", "start"), Seq.empty, Seq.empty)
        val stanzaBinaryProceed = Element(QName("binary-xml", "proceed"), Seq.empty, Seq.empty)
        val stanzaAuthRequest = Element(QName("auth", "request"), Seq.empty, Seq.empty)
        val stanzaAuthSuccess = Element(QName("auth", "success"), Seq.empty, Seq.empty)

        client.sendStreamEvent(StreamEvent.StreamOpen(Seq.empty))

        for {
          StreamEvent.StreamOpen(_) <- server.receiveStreamEvent()
          _ = server.sendStreamEvent(StreamEvent.StreamOpen(Seq.empty))
          StreamEvent.StreamOpen(_) <- client.receiveStreamEvent()
          _ = client.sendStreamEvent(StreamEvent.Stanza(stanzaBinaryStart))
          StreamEvent.Stanza(stanza) <- server.receiveStreamEvent() if stanza.qName == stanzaBinaryStart.qName
          _ = server.sendStreamEvent(StreamEvent.Stanza(stanzaBinaryProceed), resetStreams = ResetStreams.AfterWrite(BinaryXml))
          StreamEvent.Stanza(stanza) <- client.receiveStreamEvent() if stanza.qName == stanzaBinaryProceed.qName
          _ = client.sendStreamEvent(StreamEvent.StreamOpen(Seq.empty), resetStreams = ResetStreams.BeforeWrite(BinaryXml))
          StreamEvent.StreamOpen(_) <- server.receiveStreamEvent()
          _ = server.sendStreamEvent(StreamEvent.StreamOpen(Seq.empty))
          StreamEvent.StreamOpen(_) <- client.receiveStreamEvent()
          _ = client.sendStreamEvent(StreamEvent.Stanza(stanzaAuthRequest))
          StreamEvent.Stanza(stanza) <- server.receiveStreamEvent() if stanza.qName == stanzaAuthRequest.qName
          _ = server.sendStreamEvent(StreamEvent.Stanza(stanzaAuthSuccess), ResetStreams.AfterWrite())
          StreamEvent.Stanza(stanza) <- client.receiveStreamEvent() if stanza.qName == stanzaAuthSuccess.qName
          _ = client.sendStreamEvent(StreamEvent.StreamOpen(Seq.empty), ResetStreams.BeforeWrite())
          StreamEvent.StreamOpen(_) <- server.receiveStreamEvent()
        }
          yield ()
    }))


    whenReady(jobDone.future) { result =>
      log.info("Job done: {}", result)
    }
    whenReady(actorSystem.terminate())(identity)
    ()

//    whenReady(okFuture)(identity)




  }
}
