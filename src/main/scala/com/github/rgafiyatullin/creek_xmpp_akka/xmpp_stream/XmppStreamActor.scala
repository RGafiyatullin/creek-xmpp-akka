package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream
import java.nio.charset.Charset

import akka.actor.Status.{Success => AkkaSuccess}
import akka.actor.{Actor, ActorLogging, ActorRef, Props, Stash}
import akka.io.{IO, Tcp}
import com.github.rgafiyatullin.creek_xml.common.Attribute
import com.github.rgafiyatullin.creek_xmpp.streams.StreamEvent
import com.github.rgafiyatullin.owl_akka_goodies.actor_future.{ActorFuture, ActorStdReceive}

import scala.concurrent.Promise
import scala.util.Success

object XmppStreamActor {
  case object ConnectionTimeout extends Throwable
  case object ConnectionFailed extends Throwable
  case object PeerClosed extends Throwable

  private val csUtf8 = Charset.forName("UTF-8")
}

class XmppStreamActor(initArgs: XmppStreamApi.InitArgs)
  extends Actor
    with Stash
    with ActorLogging
    with ActorFuture
    with ActorStdReceive
{
  implicit val executionContext = context.dispatcher

  self ! initArgs.connectionConfig

  override def receive: Receive =
    whenAboutToInit

  def whenAboutToInit: Receive =
    handleInitConnection
      stdReceive.discard

  def handleInitConnection: Receive = {
    case XmppStreamApi.ConnectTo(addr, connectTimeout) =>
      val connectionPromise = Promise[ActorRef]
      val connectionFuture = connectionPromise.future

      context.actorOf(Props(new Actor {
        IO(Tcp)(context.system) ! Tcp.Connect(addr)
        val timeoutAlarm = context.system.scheduler
          .scheduleOnce(connectTimeout.duration, self, XmppStreamActor.ConnectionTimeout)

        override def receive = {
          case Tcp.Connected(_, _) =>
            timeoutAlarm.cancel()
            val connection = sender()
            connectionPromise.success(connection)

          case Tcp.CommandFailed(_: Tcp.Connect) =>
            timeoutAlarm.cancel()
            connectionPromise.failure(new Exception("Failed to connect to %s".format(addr)))

          case timeout @ XmppStreamActor.ConnectionTimeout =>
            connectionPromise.failure(timeout)
        }
      }))

      context become future.handle(connectionFuture) {
        case Success(connection) =>
          log.debug("Connected [connection: {}]", connection)
          connection ! Tcp.Register(self)
          whenConnected(XmppStreamData(connection, initArgs.defaultTransport.create))
      }

    case XmppStreamApi.Connected(connection) =>
      connection ! Tcp.Register(self)
      context become whenConnected(XmppStreamData(connection, initArgs.defaultTransport.create))
  }

  def whenConnected(data: XmppStreamData): Receive =
    handleExpectConnectedWhenConnected(data, whenConnected) orElse
      handleSendEvent(data, whenConnected) orElse
      handleTcpInput(data, whenConnected) orElse
      handleTcpClosed(data, whenConnected) orElse
      handleRecvEvent(data, whenConnected) orElse
      handleSwitchTransport(data, whenConnected) orElse
      stdReceive.discard

  def handleExpectConnectedWhenConnected(data: XmppStreamData, next: XmppStreamData => Receive): Receive = {
    case XmppStreamApi.api.ExpectConnected =>
      sender() ! AkkaSuccess(())
      context become next(data)
  }

  def handleRecvEvent(data: XmppStreamData, next: XmppStreamData => Receive): Receive = {
    case XmppStreamApi.api.RecvEvent(replyToOption) =>
      val akkaSender = sender()
      val promise = Promise[StreamEvent]()

      for {
        event <- promise.future
      }
        replyToOption.fold {
          akkaSender ! AkkaSuccess(event)
        }(_ ! (self, event))

      context become next(data.enquireStreamEvent(promise))
  }

  def handleSendEvent(data: XmppStreamData, next: XmppStreamData => Receive): Receive = {
    case XmppStreamApi.api.SendEvent(streamEvent) =>
      log.debug("[SEND] {}", streamEventToString(streamEvent))
      val (hles, dataEventProcessed) = data.withOutputStream(_.in(streamEvent).out)
      val (output, dataHLEsRendered) = dataEventProcessed.withTransport(_.write(hles))
      log.debug("[SEND-RAW] {}", output.toArray.map(_ & 0xff).toSeq)
      data.connection ! Tcp.Write(output)
      sender() ! AkkaSuccess(())
      context become next(dataHLEsRendered)
  }

  def handleTcpInput(data: XmppStreamData, next: XmppStreamData => Receive): Receive = {
    case Tcp.Received(bytes) =>
      log.debug("[RECV-RAW] {} {}", bytes.toArray.map(_ & 0xff).toSeq)
      val (hles, dataBytesProcessed) = data.withTransport(_.read(bytes))
      val (streamEvents, dataHLEsProcessed) = dataBytesProcessed.withInputStream(hles.foldLeft(_)(_.in(_)).outAll)
      streamEvents.foreach(se => log.debug("[RECV] {}", streamEventToString(se)))
      val dataOut = streamEvents.foldLeft(dataHLEsProcessed)(_.appendStreamEvent(_))

      context become next(dataOut)
  }

  def handleTcpClosed(data: XmppStreamData, next: XmppStreamData => Receive): Receive = {
    case Tcp.PeerClosed =>
      throw XmppStreamActor.PeerClosed
  }

  def handleSwitchTransport(data: XmppStreamData, next: XmppStreamData => Receive): Receive = {
    case XmppStreamApi.api.SwitchTransport(nextTransportFactory) =>
      val replyTo = sender()
      val nextTransport = nextTransportFactory.create
      log.info("Upgrading XMPP-transport [{} -> {}]", data.transport.name, nextTransport.name)
      context become nextTransport.handover(
        data.transport, this,
        { upgradedTransport =>
          val (_, dataWithUpgradedTransport) = data.withTransport(_ => ((), upgradedTransport))
          val (_, dataWithInputStreamExpectingStreamOpen) = dataWithUpgradedTransport.withInputStream(is => ((), is.expectStreamOpen))
          replyTo ! AkkaSuccess(())
          next(dataWithInputStreamExpectingStreamOpen)
        })
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
