package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.util

import java.net.InetSocketAddress

import akka.pattern.pipe
import akka.actor.{Actor, ActorRef, Props, Status, SupervisorStrategy}
import akka.io.{IO, Tcp}
import akka.util.Timeout
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStream
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStreamState.EventsDispatcher
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.transports.PlainXml
import com.github.rgafiyatullin.owl_akka_goodies.actor_future.ActorStdReceive

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}

object XmppServerUtil {
  type ED = EventsDispatcher[XmppStream]
  private final case class State(boundAddress: InetSocketAddress, acceptSocket: ActorRef, ed: ED) {
    def withED(edNext: ED): State = copy(ed = edNext)
  }

  final case class Config(bindAddress: InetSocketAddress)

  final case class ClientAndServer(server: XmppStream, client: XmppStream)

  final case class XmppServer(actorRef: ActorRef) extends ExpectReady with Terminate {
    import akka.pattern.ask

    def nextXmppStream(): Future[XmppStream] = {
      val promise = Promise[XmppStream]()
      actorRef ! api.GetNextXmppStream(promise)
      promise.future
    }

    def boundAddress()(implicit timeout: Timeout): Future[InetSocketAddress] =
      actorRef.ask(api.GetBoundAddress).mapTo[InetSocketAddress]
  }

  final case class XmppServerActor(config: Config) extends Actor with ActorStdReceive {
    implicit val executionContext: ExecutionContext = context.dispatcher
    val boundAddressPromise: Promise[InetSocketAddress] = Promise[InetSocketAddress]()

    override def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy

    override def receive: Receive =
      initialize()


    def initialize(): Receive = {
      log.info("Starting xmpp-server. Binding {}", config.bindAddress)
      IO(Tcp)(context.system) ! Tcp.Bind(self, config.bindAddress)
      whenBinding.receive(EventsDispatcher.create[XmppStream])
    }

    object whenBinding {
      def receive(ed: ED): Receive =
        handleBound(ed) orElse
          allStates.commonBehaviour(ed, receive) orElse
          stdReceive.discard

      def handleBound(ed: ED): Receive = {
        case Tcp.Bound(addr) =>
          log.info("Bound at {}", addr)
          val acceptSocket = sender()
          boundAddressPromise.success(addr)
          context become whenBound.receive(addr, acceptSocket, ed)

        case commandFailed @ Tcp.CommandFailed(_: Tcp.Bind) =>
          log.info("Failed to bind {}", config.bindAddress)
          val failure = new RuntimeException("Failed to bind %s".format(config.bindAddress))
          boundAddressPromise.failure(failure)
          throw failure
      }
    }

    object whenBound {

      def receive(addr: InetSocketAddress, acceptSocket: ActorRef, ed: ED): Receive =
        receive(State(addr, acceptSocket, ed))

      def receiveWithEd(state: State)(ed: ED): Receive =
        receive(state.withED(ed))

      private def receive(state: State): Receive =
        allStates.commonBehaviour(state.ed, receiveWithEd(state)) orElse
          handleConnected(state) orElse
          stdReceive.discard

      private def handleConnected(state: State): Receive = {
        case Tcp.Connected(_, _) =>
          val tcpConnection = sender()
          val xmppStream = XmppStream(
            context.actorOf(
              XmppStream.propsFromConnectionInPushMode(
                tcpConnection, PlainXml)))
          val (resolverOption, edNext) = state.ed.addEvent(xmppStream)
          resolverOption.foreach(_.apply())
          context become receiveWithEd(state)(edNext)
      }
    }

    object allStates {
      def commonBehaviour(ed: ED, next: ED => Receive): Receive =
        handleGetNextXmppStream(ed, next) orElse
          handleTerminate() orElse
          handleExpectReady() orElse
          handleGetBoundAddress()

      private def handleGetNextXmppStream(ed: ED, next: ED => Receive): Receive = {
        case api.GetNextXmppStream(promise) =>
          val (resolverOption, edNext) = ed.addConsumer(promise)
          resolverOption.foreach(_.apply())
          context become next(edNext)
      }

      private def handleExpectReady(): Receive = {
        case ExpectReady.Query() =>
          boundAddressPromise.future.map(_ => ()).pipeTo(sender())
          ()
      }

      private def handleTerminate(): Receive = {
        case Terminate.Query() =>
          sender() ! Status.Success(())
          context stop self
          context become stdReceive.discard
      }

      private def handleGetBoundAddress(): Receive = {
        case api.GetBoundAddress =>
          boundAddressPromise.future.pipeTo(sender())
          ()
      }
    }
  }

  object api {
    final case class GetNextXmppStream(promise: Promise[XmppStream])
    case object GetBoundAddress
  }
}

trait XmppServerUtil extends ActorSystemUtil {
  import XmppServerUtil.{XmppServer, ClientAndServer}

  def withXmppServer[T](f: XmppServer => Future[T]): Future[T] =
    withActorSystem("xmpp-server-" + randomString()) { actorSystem =>
      implicit val timeout: Timeout = 5.seconds
      implicit val ec: ExecutionContext = actorSystem.dispatcher

      val config = XmppServerUtil.Config(new InetSocketAddress("127.0.0.1", 0))
      val xmppServer = XmppServer(
        actorSystem.actorOf(
          Props(classOf[XmppServerUtil.XmppServerActor], config)))
      for {
        result <- f(xmppServer)
        _ <- xmppServer.terminate()
      }
        yield result
    }

  def withClientAndServer[T](f: (XmppServer, ClientAndServer) => Future[T])(implicit timeout: Timeout): Future[T] =
    withActorSystem() { actorSystem =>
      implicit val ec: ExecutionContext = actorSystem.dispatcher
      withXmppServer { xmppServer =>
        for {
          _ <- xmppServer.expectReady()
          connectTo <- xmppServer.boundAddress()
          client = XmppStream(actorSystem.actorOf(XmppStream.propsConnectTo(connectTo, PlainXml)))
          server <- xmppServer.nextXmppStream()
          result <- f(xmppServer, ClientAndServer(server, client))
        }
          yield result
      }
    }
}