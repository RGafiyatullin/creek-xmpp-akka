package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Status}
import akka.io.{IO, Tcp}
import akka.util.Timeout
import akka.pattern.pipe
import com.github.rgafiyatullin.creek_xml.common.Attribute
import com.github.rgafiyatullin.creek_xmpp.streams.{InputStream, OutputStream, StreamEvent}
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStream.api.ResetStreams
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStreamState.EventsDispatcher
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.transports.{PlainXml, XmppTransport, XmppTransportFactory}
import com.github.rgafiyatullin.owl_akka_goodies.actor_future.ActorStdReceive


import scala.collection.immutable.Queue
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.{Success, Try}

object XmppStream {
  def props(
    connectionArgs: ConnectionArgs,
    transportFactory: XmppTransportFactory = PlainXml)
  : Props =
    Props(classOf[XmppStreamActor], connectionArgs, transportFactory)

  def propsConnectTo(to: InetSocketAddress, xmppTransportFactory: XmppTransportFactory, timeoutOption: Option[Timeout] = None): Props =
    props(ConnectionArgs.Connect(to, timeoutOption), xmppTransportFactory)

  def propsFromConnectionInPushMode(connection: ActorRef, xmppTransportFactory: XmppTransportFactory): Props =
    props(ConnectionArgs.FromConnectionInPushMode(connection), xmppTransportFactory)

  def propsEmpty(xmppTransportFactory: XmppTransportFactory): Props =
    props(ConnectionArgs.Empty, xmppTransportFactory)



  sealed trait ConnectionArgs
  object ConnectionArgs {
    final case class Connect(
      to: InetSocketAddress,
      timeout: Option[Timeout] = None)
        extends ConnectionArgs

    final case class FromConnectionInPushMode(connection: ActorRef) extends ConnectionArgs
//    final case class FromConnectionInPullMode(connection: ActorRef) extends ConnectionArgs

    case object Empty extends ConnectionArgs
  }

  final class XmppStreamActor(
    connectionArgs: ConnectionArgs,
    transportFactory: XmppTransportFactory)
      extends Actor
        with ActorLogging
        with ActorStdReceive
  {
    implicit val executionContext: ExecutionContext = context.dispatcher
    val ownerRegistrationTimeout: Timeout = 5.seconds

    val connectedPromise: Promise[Unit] = Promise[Unit]()

    val ownerWatchSat: ActorRef = context.actorOf(
      XmppStreamOwnerWatchSat.props(self, ownerRegistrationTimeout),
      "owner-watch-sat")

    override def receive: Receive = initialize()

    def createTransport(): XmppTransport = transportFactory.create

    def createEventsDispatcher(): EventsDispatcher[StreamEvent] =
      XmppStreamState.EventsDispatcher.create[StreamEvent]

    def initialize(): Receive =
      connectionArgs match {
        case ConnectionArgs.Empty =>
          log.debug("initialize empty -> whenEmpty")

          whenEmpty.receive(
            XmppStreamState.Empty(
              createEventsDispatcher()))

//        case ConnectionArgs.FromConnectionInPullMode(connection) =>
//          log.debug("initialize from connection[pull-mode]")
//          ???

        case ConnectionArgs.FromConnectionInPushMode(connection) =>
          log.debug("initialize from connection[push-mode] -> whenConnected [connection: {}]", connection)

          connection ! Tcp.Register(self)
          connectedPromise.success(())

          whenConnected.receive(
            XmppStreamState.Connected(
              connection = connection,
              transport = createTransport(),
              eventsDispatcher = createEventsDispatcher()))

        case ConnectionArgs.Connect(toSocketAddress, timeoutOption) =>
          log.debug("initialize connect to -> whenConnecting [to: {}; timeout: {}]", toSocketAddress, timeoutOption)

          IO(Tcp)(context.system) ! Tcp.Connect(toSocketAddress, timeout = timeoutOption.map(_.duration))
          whenConnecting.receive(
            XmppStreamState.Connecting(
              eventsDispatcher = createEventsDispatcher()))
      }

    object allStates {
      def handleExpectConnected(): Receive = {
        case api.ExpectConnected() =>
          connectedPromise.future.pipeTo(sender())
          ()
      }

      def handleRegisterOwner(): Receive = {
        case msg: api.RegisterOwner =>
          ownerWatchSat ! msg
      }

      def handleTerminate(): Receive = {
        case api.Terminate() =>
          sender() ! Status.Success(())
          context stop self
          context become stdReceive.discard
      }

      def handleSendStreamEventWhileInvalidState(stateName: String): Receive = {
        case api.SendStreamEvent(se, _) =>
          log.warning(
            "attempt to send stream-event while in invalid state [state: {}; event: {}]",
            stateName, util.streamEventToString(se))
      }
    }

    object whenEmpty {
      def receive(state: XmppStreamState.Empty): Receive =
        allStates.handleExpectConnected() orElse
          allStates.handleRegisterOwner() orElse
          allStates.handleSendStreamEventWhileInvalidState("empty") orElse
          allStates.handleTerminate() orElse
          stdReceive.discard
    }

    object whenConnecting {
      def receive(state: XmppStreamState.Connecting): Receive =
        allStates.handleExpectConnected() orElse
          allStates.handleRegisterOwner() orElse
          handleConnected(state) orElse
          handleCommandFailed(state) orElse
          allStates.handleSendStreamEventWhileInvalidState("when-connecting") orElse
          allStates.handleTerminate() orElse
          stdReceive.discard

      private def handleCommandFailed(state: XmppStreamState.Connecting): Receive = {
        case Tcp.CommandFailed(_: Tcp.Connect) =>
          log.debug("whenConnecting -> whenFailed [connection failed]")
          connectedPromise.failure(api.ConnectionFailure())

          context become whenFailed.receive(state.toFailed)
      }

      private def handleConnected(state: XmppStreamState.Connecting): Receive = {
        case Tcp.Connected(remoteAddress, localAddress) =>
          val connection = sender()
          val transport = createTransport()
          log.debug(
            "whenConnecting -> whenConnected [connection: {}; remote: {}; local: {}; transport: {}]",
            connection, remoteAddress, localAddress, transport.name)

          connection ! Tcp.Register(self)
          connectedPromise.success(())

          context become whenConnected.receive(
            state.toConnected(connection, transport))
      }
    }

    object whenFailed {
      def receive(state: XmppStreamState.Failed): Receive =
        allStates.handleExpectConnected() orElse
          allStates.handleRegisterOwner() orElse
          allStates.handleSendStreamEventWhileInvalidState("failed") orElse
          allStates.handleTerminate() orElse
          stdReceive.discard
    }

    object whenClosed {
      def receive(state: XmppStreamState.Connected): Receive =
        allStates.handleExpectConnected() orElse
          allStates.handleRegisterOwner() orElse
          allStates.handleSendStreamEventWhileInvalidState("closed") orElse
          allStates.handleTerminate() orElse
          stdReceive.discard
    }

    object whenConnected {
      def receive(state: XmppStreamState.Connected): Receive =
        allStates.handleExpectConnected() orElse
          allStates.handleRegisterOwner() orElse
          handleTcpReceived(state) orElse
          handleSendStreamEvent(state) orElse
          handleReceiveStreamEvent(state) orElse
          handleTcpPeerClosed(state) orElse
          handleTcpError(state) orElse
          handleTerminate(state) orElse
          stdReceive.discard

      private def handleTerminate(state: XmppStreamState.Connected): Receive = {
        case api.Terminate() =>
          state.eventsDispatcher.consumers.foreach(_.failure(api.TerminationRequested(sender())))
          sender() ! Status.Success(())
          context stop self
          context become stdReceive.discard
      }

      private def handleSendStreamEvent(state: XmppStreamState.Connected): Receive = {
        case api.SendStreamEvent(event, resetStreams) =>
          log.debug("[SEND] {}", util.streamEventToString(event))
          val state1 =
            if (resetStreams.before) util.resetStreams(state, resetStreams.transportFactoryOption)
            else state

          val (xmlEvents, state2) = state1.usingOutputStream(_.in(event).out)
//          xmlEvents.foreach(xe => log.debug("[SEND-XMLEvent] {}", xe))
          val (outboundBytes, state3) = state2.usingTransport(_.write(xmlEvents))

          val state4 =
            if (resetStreams.after) util.resetStreams(state3, resetStreams.transportFactoryOption)
            else state3

//          log.debug("[SEND-RAW] {}", outboundBytes.toArray.map(_ & 0xff).toSeq)
          state4.connection ! Tcp.Write(outboundBytes)
          sender() ! Status.Success(())

          context become receive(state4)
      }

      private def handleReceiveStreamEvent(state: XmppStreamState.Connected): Receive = {
        case api.ReceiveStreamEvent(promise) if state.inputStreamFailureOption.isDefined =>
          promise.failure(state.inputStreamFailureOption.get)
          context become receive(state)

        case api.ReceiveStreamEvent(promise) if state.inputStreamFailureOption.isEmpty =>
          val (resolverOption, stateNext) = state.usingEventsDispatcher(_.addConsumer(promise))
          resolverOption.foreach(_.apply())
          context become receive(stateNext)
      }

      private def handleTcpPeerClosed(state: XmppStreamState.Connected): Receive = {
        case Tcp.PeerClosed =>
          state.eventsDispatcher.consumers.foreach(_.failure(api.TcpPeerClosed()))
          context become whenClosed.receive(state)
      }

      private def handleTcpError(state: XmppStreamState.Connected): Receive = {
        case Tcp.ErrorClosed(reason) =>
          state.eventsDispatcher.consumers.foreach(_.failure(api.TcpErrorClosed(reason)))
          context become whenClosed.receive(state)
      }

      private def handleTcpReceived(state: XmppStreamState.Connected): Receive = {
        case Tcp.Received(_) if state.inputStreamFailureOption.isDefined =>
          log.debug("Ignoring Tcp.Received due to input stream failure")
          context become receive(state)

        case Tcp.Received(inboundBytes) if state.inputStreamFailureOption.isEmpty =>

          def processInboundBytes(): (Seq[StreamEvent], XmppStreamState.Connected) = {
            //          log.debug("[RECV-RAW] {}", inboundBytes.toArray.map(_ & 0xff).toSeq)
            val (xmlEvents, state1) = state.usingTransport(_.read(inboundBytes))
            //          xmlEvents.foreach(xe => log.debug("[RECV-XMLEvent] {}", xe))
            val (xmppEvents, state2) = state1.usingInputStream(xmlEvents.foldLeft(_)(_.in(_)).outAll)
            xmppEvents.foreach(se => log.debug("[RECV] {}", util.streamEventToString(se)))

            (xmppEvents, state2)
          }

          val Success(nextReceive) = Try {
            val (xmppEvents, state2) = processInboundBytes()

            val (resolvers, state3) = xmppEvents.foldLeft((Queue.empty[() => Unit], state2)) {
              case ((resolversAcc, stateIn), StreamEvent.LocalError(xmppStreamError)) =>
                val (failTheRestResolver, stateOut) = stateIn.usingEventsDispatcher { ed =>
                    (() => ed.consumers.foreach(_.failure(xmppStreamError)), ed)
                  }
                (resolversAcc.enqueue(failTheRestResolver), stateOut.withInputStreamFailure(xmppStreamError))

              case ((resolversAcc, stateIn), streamEvent) =>
                val (resolverOption, stateOut) = stateIn.usingEventsDispatcher(_.addEvent(streamEvent))
                (resolversAcc ++ resolverOption.toTraversable, stateOut)
            }

            resolvers.foreach(_.apply())
            receive(state3)
          } recover {
            case e: Exception =>
              state.eventsDispatcher.consumers.foreach(_.failure(e))
              receive(state.withInputStreamFailure(e))
          }

          context become nextReceive
      }
    }


    object util {
      def resetStreams(state: XmppStreamState.Connected, transportFactoryOption: Option[XmppTransportFactory]): XmppStreamState.Connected = {
        val state1 = state.usingInputStream(_ => ((), InputStream.empty))._2
        val state2 = state1.usingOutputStream(_ => ((), OutputStream.empty))._2
        val state3 = transportFactoryOption.fold {
          state2.usingTransport(t => ((), t.reset))._2
        }{ tf =>
          state2.usingTransport(_ => ((), tf.create))._2
        }

        state3
      }

      def streamEventToString(se: StreamEvent): String =
        se match {
          case StreamEvent.StreamOpen(attrs) =>
            "STREAM-OPEN[" + attrs.map {
              case Attribute.Unprefixed(k, v) => k + " -> " + v
              case Attribute.Prefixed(p, k, v) => p + ":" + k + " -> " + v
              case Attribute.NsImport(p, ns) => "xmlns:" + p + " -> " + ns
            }.mkString(", ") + "]"

          case StreamEvent.StreamClose() =>
            "STREAM-CLOSE"

          case StreamEvent.Stanza(xml) =>
            "STANZA: " + xml.rendered

          case StreamEvent.RemoteError(error) =>
            "REMOTE-ERROR: " + error.toString

          case StreamEvent.LocalError(error) =>
            "LOCAL-ERROR: " + error.toString
        }
    }

  }

  object api {
    object RegisterOwner {
      case object RegisterTimeout
    }
    final case class RegisterOwner(owner: ActorRef)

    sealed trait Shutdown extends Exception
    final case class TcpErrorClosed(reason: String) extends Shutdown {
      override def getMessage: String = "Tcp error closed"
    }
    final case class TcpPeerClosed() extends Shutdown {
      override def getMessage: String = "Tcp peer closed"
    }
    final case class TerminationRequested(by: ActorRef) extends Shutdown {
      override def getMessage: String = "Termination requested [by: %s]".format(by)
    }

    final case class ConnectionFailure() extends Exception {
      override def getMessage: String = "Connection failure"
    }

    final case class ReceiveStreamEvent(promise: Promise[StreamEvent])
    final case class SendStreamEvent(event: StreamEvent, resetStreams: ResetStreams)

    final case class Terminate()

    sealed trait ResetStreams {
      def before: Boolean
      def after: Boolean
      def transportFactoryOption: Option[XmppTransportFactory]
    }
    object ResetStreams {
      case object No extends ResetStreams {
        override def before = false
        override def after = false
        override def transportFactoryOption: Option[XmppTransportFactory] = None
      }

      object AfterWrite {
        def apply(): AfterWrite = new AfterWrite(None)
        def apply(transportFactory: XmppTransportFactory): AfterWrite = new AfterWrite(Some(transportFactory))
      }
      final class AfterWrite private(override val transportFactoryOption: Option[XmppTransportFactory]) extends ResetStreams {
        override def before = false
        override def after = true
      }

      object BeforeWrite {
        def apply(): BeforeWrite = new BeforeWrite(None)
        def apply(transportFactory: XmppTransportFactory): BeforeWrite = new BeforeWrite(Some(transportFactory))
      }
      final class BeforeWrite private(override val transportFactoryOption: Option[XmppTransportFactory]) extends ResetStreams {
        override def before = true
        override def after = false
      }
    }

    final case class ExpectConnected()

  }
}

final case class XmppStream(actorRef: ActorRef) {
  import XmppStream.api
  import akka.pattern.ask

  def registerOwner(owner: ActorRef): Unit =
    actorRef ! api.RegisterOwner(owner)

  def receiveStreamEvent(): Future[StreamEvent] = {
    val p = Promise[StreamEvent]()
    actorRef ! api.ReceiveStreamEvent(p)
    p.future
  }

  def expectConnected()(implicit timeout: Timeout): Future[Unit] =
    actorRef.ask(api.ExpectConnected()).mapTo[Unit]

  def sendStreamEvent(event: StreamEvent, resetStreams: ResetStreams = ResetStreams.No)(implicit timeout: Timeout): Future[Unit] =
    actorRef.ask(api.SendStreamEvent(event, resetStreams)).mapTo[Unit]

  def terminate()(implicit timeout: Timeout): Future[Unit] =
    actorRef.ask(api.Terminate()).mapTo[Unit]

//  def resetInputStream(transportUpgradeOption: Option[XmppTransportFactory]) =
//    actorRef ! api.ResetInputStream(transportUpgradeOption)
}
