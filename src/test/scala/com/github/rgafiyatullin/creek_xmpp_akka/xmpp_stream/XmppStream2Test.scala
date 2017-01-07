package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorRefFactory, ActorSystem}
import akka.io.{IO, Tcp}
import akka.util.Timeout
import akka.testkit.TestActorRef
import com.github.rgafiyatullin.creek_xmpp.streams.StreamEvent
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.transports.BinaryXml
import com.github.rgafiyatullin.owl_akka_goodies.actor_future.ActorStdReceive
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.Promise
import scala.concurrent.duration._


class XmppStream2Test extends FlatSpec with Matchers with ScalaFutures {
  org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.DEBUG)

  implicit val defaultPatience =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(500, Millis))

  implicit val actorSystem = ActorSystem("host-test")
  implicit val actorRefFactory: ActorRefFactory = actorSystem
  implicit val executionContext = actorSystem.dispatcher
  implicit val defaultTimeout: Timeout = Timeout(5.seconds)


  "XMPP streams" should "communicate with each other" in {
    val inetSocketAddress = new InetSocketAddress("127.0.0.1", 10002)

    class TcpAcceptor(isa: InetSocketAddress, promiseServer: Promise[XmppStreamApi], promiseBound: Promise[Unit]) extends Actor with ActorStdReceive {
      val tcpMgr = IO(Tcp)

      tcpMgr ! Tcp.Bind(self, isa)

      override def receive = {
        case Tcp.Bound(addr) =>
          println("BOUND! [%s]".format(addr))
          promiseBound.success(())
          context become whenBound()
      }

      def whenBound(): Receive = {
        case Tcp.Connected(_, _) =>
          println("CONNECTED!")
          val connection = sender()
          val server = XmppStreamApi.create(
            XmppStreamApi.InitArgs(
              XmppStreamApi.Connected(connection)
            ), "server")(actorRefFactory)
          promiseServer.success(server)
      }
    }

    val promiseServer = Promise[XmppStreamApi]()
    val promiseBound = Promise[Unit]()
    val tcpAcceptor = TestActorRef(new TcpAcceptor(inetSocketAddress, promiseServer, promiseBound))

    val futClient =
      for {
        _ <- promiseBound.future
      }
        yield XmppStreamApi.create(
          XmppStreamApi.InitArgs(
            XmppStreamApi.ConnectTo(inetSocketAddress)), "client")

    val futBothConnected =
      for {
        server <- promiseServer.future
        client <- futClient
        _ <- server.expectConnected()
        _ <- client.expectConnected()
      }
        yield (server, client)

    val futStreamsOpenned =
      for {
        (server, client) <- futBothConnected
        _ <- client.sendEvent(StreamEvent.StreamOpen(Seq()))
        streamOpenFromClient <- server.recvEvent()
        _ <- server.sendEvent(StreamEvent.StreamOpen(Seq()))
        streamOpenFromServer <- client.recvEvent()
      }
        yield (server, client)

    val futTransportsUpgraded =
      for {
        (server, client) <- futStreamsOpenned
        _ <- client.switchTransport(BinaryXml)
        _ <- server.switchTransport(BinaryXml)

      }
        yield (server, client)

    val futStreamsReopenned =
      for {
        (server, client) <- futTransportsUpgraded
        _ <- client.sendEvent(StreamEvent.StreamOpen(Seq()))
        streamOpenFromClient <- server.recvEvent()
        _ <- server.sendEvent(StreamEvent.StreamOpen(Seq()))
        streamOpenFromServer <- client.recvEvent()
      }
        yield (server, client)

    whenReady(futBothConnected)(_ =>  println("both connected"))
    whenReady(futStreamsOpenned)(_ => println("both streams openned"))
    whenReady(futTransportsUpgraded)(_ => println("both transports upgraded"))
    whenReady(futStreamsReopenned)(_ => println("both streams reopenned"))
  }
}
