package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream

import java.net.InetSocketAddress
import java.util.UUID

import akka.pattern.ask
import akka.actor.{ActorRef, ActorRefFactory, Props}
import akka.util.Timeout
import com.github.rgafiyatullin.creek_xmpp.streams.StreamEvent
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.transports.{PlainXml, XmppTransportFactory}

import scala.concurrent.duration._
import scala.concurrent.Future

object XmppStreamApi {
  sealed trait ConnectionConfig
  case class Connected(connection: ActorRef) extends ConnectionConfig
  case class ConnectTo(inetSocketAddress: InetSocketAddress, connectTimeout: Timeout = 5.seconds) extends ConnectionConfig

  object api {
    case object ExpectConnected
    final case class SendEvent(streamEvent: StreamEvent)
    final case class RecvEvent(replyToOption: Option[ActorRef])
    final case class SwitchTransport(transport: XmppTransportFactory)
  }


  final case class InitArgs(
    connectionConfig: ConnectionConfig,
    defaultTransport: XmppTransportFactory = PlainXml)

  def create(initArgs: InitArgs, name: String = "xmpp-stream-" + UUID.randomUUID().toString)(implicit arf: ActorRefFactory): XmppStreamApi =
    XmppStreamApi(arf.actorOf(Props(classOf[XmppStreamActor], initArgs), name))
}

final case class XmppStreamApi(actor: ActorRef) {
  def expectConnected()(implicit ct: Timeout): Future[Unit] =
    actor.ask(XmppStreamApi.api.ExpectConnected).mapTo[Unit]

  def switchTransport(transport: XmppTransportFactory)(implicit ct: Timeout): Future[Unit] =
    actor.ask(XmppStreamApi.api.SwitchTransport(transport)).mapTo[Unit]

  def recvEvent()(implicit ct: Timeout): Future[StreamEvent] =
    actor.ask(XmppStreamApi.api.RecvEvent(None)).mapTo[StreamEvent]

  def orderEvent(replyTo: ActorRef)(implicit ct: Timeout): Unit =
    actor ! XmppStreamApi.api.RecvEvent(Some(replyTo))


  def sendEvent(event: StreamEvent)(implicit ct: Timeout): Future[Unit] =
    actor.ask(XmppStreamApi.api.SendEvent(event)).mapTo[Unit]
}
