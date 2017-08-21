package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorSystem, Props, SupervisorStrategy}
import akka.event.Logging.LogLevel
import akka.io.{IO, Tcp}
import akka.util.Timeout
import com.github.rgafiyatullin.creek_xml.common.QName
import com.github.rgafiyatullin.creek_xml.dom.Element
import com.github.rgafiyatullin.creek_xmpp.streams.StreamEvent
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStream.api.ResetStreams
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.transports.{BinaryXml, PlainXml}
import com.github.rgafiyatullin.owl_akka_goodies.actor_future.{ActorFuture, ActorStdReceive}
import org.scalatest.FlatSpec
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.reflect.ClassTag

class XmppStreamPushModeTest extends FlatSpec with ScalaFutures {

  org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.DEBUG)

  implicit val timeout: Timeout = 5.seconds

  implicit val defaultPatience: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(500, Millis))


  class Sup(done: Promise[Any], task: (XmppStream, XmppStream) => Future[Any]) extends Actor with ActorFuture with ActorStdReceive {
    implicit val executionContext: ExecutionContext = context.dispatcher

    override def receive: Receive = initialize()

    override def supervisorStrategy: SupervisorStrategy =
      SupervisorStrategy.stoppingStrategy

    def whenBinding(): Receive = {
      case Tcp.Bound(localAddress) =>
        log.info("Bound at {}", localAddress)
        val connectTo = new InetSocketAddress("127.0.0.1", localAddress.getPort)
        val clientActorRef = context.actorOf(XmppStream.propsConnectTo(connectTo, PlainXml, Some(3.seconds)), "client")
        val client = XmppStream(clientActorRef)
        client.registerOwner(self)
        context become whenAccepting(client)
    }


    def whenAccepting(client: XmppStream): Receive = {
      case Tcp.Connected(remoteAddress, localAddress) =>
        val connection = sender()
        log.info("Accepted {} -> {} [conn: {}]", remoteAddress, localAddress, connection)
        val serverActorRef = context.actorOf(XmppStream.propsFromConnectionInPushMode(connection, PlainXml), "server")
        val server = XmppStream(serverActorRef)
        server.registerOwner(self)
        log.info("About to become whenAccepted")

        task(client, server).onComplete(done.complete)

        context become stdReceive.discard
    }

    def initialize(): Receive = {
      IO(Tcp)(context.system) ! Tcp.Bind(self, new InetSocketAddress(0))
      whenBinding()
    }
  }

  def withClientAndServer[Ret](f: (ActorSystem, XmppStream, XmppStream) => Future[Ret])(implicit classTag: ClassTag[Ret]): Ret = {
    val actorSystem = ActorSystem()
    try {
      val jobDone = Promise[Any]()
      actorSystem.eventStream.setLogLevel(LogLevel(4))
      implicit val executionContext: ExecutionContext = actorSystem.dispatcher
      val log = actorSystem.log

      actorSystem.actorOf(Props(new Sup(jobDone, f(actorSystem, _, _) )), "sup")

      whenReady(jobDone.future.mapTo[Ret])(identity)
    } finally {
      whenReady(actorSystem.terminate())(_ => ())
    }
  }

  "server stream" should "open and close streams" in {
    withClientAndServer { case (actorSystem, client, server) =>
      implicit val ec: ExecutionContext = actorSystem.dispatcher

      client.sendStreamEvent(StreamEvent.StreamOpen(Seq.empty))
      client.sendStreamEvent(StreamEvent.StreamClose())
      for {
        StreamEvent.StreamOpen(_) <- server.receiveStreamEvent()
        StreamEvent.StreamClose() <- server.receiveStreamEvent()
      }
        yield ()
    }
  }

  "client stream" should "open and close streams" in {
    withClientAndServer { case (actorSystem, client, server) =>
      implicit val ec: ExecutionContext = actorSystem.dispatcher

      server.sendStreamEvent(StreamEvent.StreamOpen(Seq.empty))
      server.sendStreamEvent(StreamEvent.StreamClose())
      for {
        StreamEvent.StreamOpen(_) <- client.receiveStreamEvent()
        StreamEvent.StreamClose() <- client.receiveStreamEvent()
      }
        yield ()
    }
  }

  "streams" should "open and reopen with changing transport" in {
    withClientAndServer { case (actorSystem, client, server) =>
      implicit val ec: ExecutionContext = actorSystem.dispatcher

      val stanzaBinaryStart = Element(QName("binary-xml", "start"), Seq.empty, Seq.empty)
      val stanzaBinaryProceed = Element(QName("binary-xml", "proceed"), Seq.empty, Seq.empty)

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

        _ = client.sendStreamEvent(StreamEvent.StreamClose())
        StreamEvent.StreamClose() <- server.receiveStreamEvent()
        _ = server.sendStreamEvent(StreamEvent.StreamClose())
        StreamEvent.StreamClose() <- client.receiveStreamEvent()

        _ <- client.terminate()
        _ <- server.terminate()
      }
        yield ()
    }
  }

  they should "open and reopen streams without transport switch" in {
    withClientAndServer { case (actorSystem, client, server) =>
      implicit val ec: ExecutionContext = actorSystem.dispatcher

      val stanzaAuthRequest = Element(QName("auth", "request"), Seq.empty, Seq.empty)
      val stanzaAuthSuccess = Element(QName("auth", "success"), Seq.empty, Seq.empty)

      client.sendStreamEvent(StreamEvent.StreamOpen(Seq.empty))

      for {
        StreamEvent.StreamOpen(_) <- server.receiveStreamEvent()
        _ = server.sendStreamEvent(StreamEvent.StreamOpen(Seq.empty))
        StreamEvent.StreamOpen(_) <- client.receiveStreamEvent()

        _ = client.sendStreamEvent(StreamEvent.Stanza(stanzaAuthRequest))
        StreamEvent.Stanza(stanza) <- server.receiveStreamEvent() if stanza.qName == stanzaAuthRequest.qName
        _ = server.sendStreamEvent(StreamEvent.Stanza(stanzaAuthSuccess), ResetStreams.AfterWrite())
        StreamEvent.Stanza(stanza) <- client.receiveStreamEvent() if stanza.qName == stanzaAuthSuccess.qName
        _ = client.sendStreamEvent(StreamEvent.StreamOpen(Seq.empty), ResetStreams.BeforeWrite())
        StreamEvent.StreamOpen(_) <- server.receiveStreamEvent()
        _ = server.sendStreamEvent(StreamEvent.StreamOpen(Seq.empty))
        StreamEvent.StreamOpen(_) <- client.receiveStreamEvent()

        _ = client.sendStreamEvent(StreamEvent.StreamClose())
        StreamEvent.StreamClose() <- server.receiveStreamEvent()
        _ = server.sendStreamEvent(StreamEvent.StreamClose())
        StreamEvent.StreamClose() <- client.receiveStreamEvent()

        _ <- client.terminate()
        _ <- server.terminate()
      }
        yield ()
    }
  }

  they should "open and reopen streams with and then without transport switch" in {
    withClientAndServer { case (actorSystem, client, server) =>
      implicit val ec: ExecutionContext = actorSystem.dispatcher

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
        _ = server.sendStreamEvent(StreamEvent.StreamOpen(Seq.empty))
        StreamEvent.StreamOpen(_) <- client.receiveStreamEvent()


        _ = client.sendStreamEvent(StreamEvent.StreamClose())
        StreamEvent.StreamClose() <- server.receiveStreamEvent()
        _ = server.sendStreamEvent(StreamEvent.StreamClose())
        StreamEvent.StreamClose() <- client.receiveStreamEvent()

        _ <- client.terminate()
        _ <- server.terminate()
      }
        yield ()
    }
  }

  they should "open and reopen streams with and then without transport switch (with transport logging enabled)" in {
    withClientAndServer { case (actorSystem, client, server) =>
      implicit val ec: ExecutionContext = actorSystem.dispatcher
      import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.transports.LoggingTransport.implicits._

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
        _ = server.sendStreamEvent(StreamEvent.Stanza(stanzaBinaryProceed), resetStreams = ResetStreams.AfterWrite(BinaryXml.logging.enabled(actorSystem.log)))
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
        _ = server.sendStreamEvent(StreamEvent.StreamOpen(Seq.empty))
        StreamEvent.StreamOpen(_) <- client.receiveStreamEvent()


        _ = client.sendStreamEvent(StreamEvent.StreamClose())
        StreamEvent.StreamClose() <- server.receiveStreamEvent()
        _ = server.sendStreamEvent(StreamEvent.StreamClose())
        StreamEvent.StreamClose() <- client.receiveStreamEvent()

        _ <- client.terminate()
        _ <- server.terminate()
      }
        yield ()
    }
  }

}
