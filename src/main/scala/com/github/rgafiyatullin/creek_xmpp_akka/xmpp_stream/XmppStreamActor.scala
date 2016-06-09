package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream

import akka.actor.{Actor, ActorLogging, ActorRef, Stash}
import akka.io.{IO, Tcp}
import akka.util.ByteString
import com.github.rgafiyatullin.creek_xmpp.streams.StreamEvent
import com.github.rgafiyatullin.creek_xmpp_akka.XmppStream
import com.github.rgafiyatullin.creek_xmpp_akka.common.actor_api.{Err, Ok}

import scala.annotation.tailrec

class XmppStreamActor(config: XmppStream.Config)
  extends Actor with ActorLogging with Stash
{
  override def receive: Receive =
    config.connection match {
      case XmppStream.Connected(connection) =>
        whenConnected(Data.create(config, connection))

      case XmppStream.ConnectTo(inetSocketAddress) =>
        IO(Tcp)(context.system) ! Tcp.Connect(inetSocketAddress)
        whenConnecting
    }

  private def whenConnecting: Receive = {
    case Tcp.Connected(remoteAddress, localAddress) =>
      val connection = sender()
      log.info(
        "Connected. Remote: {}; Local: {}; Connection: {}",
        remoteAddress, localAddress, connection)
      unstashAll()
      connection ! Tcp.Register(self)
      context become whenConnected(Data.create(config, connection))

    case Tcp.CommandFailed(_: Tcp.Connect) =>
      log.warning("Connection failed")
      context stop(self)

    case anything =>
      log.debug("Stashing: {}", anything)
      stash()
  }

  private def whenConnected(data: Data): Receive = {
    case Api.SendEvent(outboundEvent) =>
      context become
        whenConnected(
          handleAskSendEvent(outboundEvent, data))

    case Api.RecvEvent(maybeTo) =>
      context become
        whenConnected(
          handleAskRecvEvent(tcpClosed = false, maybeTo, data))

    case Tcp.Received(inBytes) =>
      context become
        whenConnected(
          handleTcpReceived(inBytes, data))

    case Tcp.PeerClosed =>
      context become
        whenDisconnected(data)

    case anything =>
      log.debug("whenConnected: skipping {}", anything)
  }

  private def whenDisconnected(data: Data): Receive = {
    case Api.SendEvent(_) =>
      sender() ! Err(Api.SendError.TcpClosed)

    case Api.RecvEvent(maybeTo) =>
      context become
        whenDisconnected(
          handleAskRecvEvent(tcpClosed = true, maybeTo, data))

    case anything =>
      log.debug("whenDisconnected: skipping {}", anything)
  }


  private def handleAskSendEvent(outboundEvent: StreamEvent, data0: Data): Data = {
    val replyTo = sender()

    val os0 = data0.outputStream.in(outboundEvent)
    val (outStrings, os1) = os0.out

    val outString = outStrings.mkString
    val tcpCommand = Tcp.Write(ByteString(outString))
    log.debug("[XMPP_OUT]: {}", outString)

    data0.tcp ! tcpCommand

    val data1 = data0.copy(outputStream = os1)

    replyTo ! Ok
    data1
  }

  private def handleAskRecvEvent(tcpClosed: Boolean, maybeTo: Option[ActorRef], data0: Data): Data = {
    val replyTo = maybeTo.getOrElse(sender())
    val ieq = data0.inboundEvents
    val rtq = data0.inboundEventReplyTos

    val data1 =
      ieq.headOption.fold {
        if (tcpClosed) {
          replyTo ! Err(Api.RecvError.TcpClosed)
          data0
        }
        else
          data0.copy(inboundEventReplyTos = rtq.enqueue(replyTo))
      } { event =>
        replyToRecvEvent(replyTo, event)
        data0.copy(inboundEvents = ieq.tail)
      }

    data1
  }

  private def handleTcpReceived(inBytes: ByteString, data0: Data): Data = {
    log.debug("Received: {} bytes. Feeding inputStream", inBytes.length)
    val is0 = inBytes.foldLeft(data0.inputStream){
      case (is, b) => is.in(b.toChar)
    }
    log.debug("[XMPP_IN]: {}", is0.parser.inputBuffer.mkString)

    val data1 = processInputStreamEventsLoop(data0.copy(inputStream = is0))
    data1
  }

  @tailrec
  private def processInputStreamEventsLoop(data0: Data): Data = {
    data0.inputStream.out match {
      case (None, nextIs) =>
//        log.debug("inputStream: no more events available. {}", nextIs)
        data0.copy(inputStream = nextIs)

      case (Some(event), nextIs) =>
//        log.debug("inputStream: event {}", event)
        val data1 =
          data0.inboundEventReplyTos.headOption.fold {
            data0.copy(inboundEvents = data0.inboundEvents.enqueue(event))
          } { replyTo =>
            replyToRecvEvent(replyTo, event)
            data0.copy(inboundEventReplyTos = data0.inboundEventReplyTos.tail)
          }

        processInputStreamEventsLoop(data1.copy(inputStream = nextIs))
    }
  }

  private def replyToRecvEvent(replyTo: ActorRef, event: StreamEvent): Unit = {
    val replyWith = Ok(event)
    replyTo ! replyWith
  }
}
