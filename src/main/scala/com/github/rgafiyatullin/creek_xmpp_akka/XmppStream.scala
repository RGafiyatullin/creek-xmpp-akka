package com.github.rgafiyatullin.creek_xmpp_akka

import java.net.InetSocketAddress

import akka.actor.{ActorRef, ActorRefFactory, Props}

object XmppStream {
  type Actor = xmpp_stream.XmppStreamActor
  type Api = xmpp_stream.Api

  sealed trait ConnectionConfig
  case class Connected(connection: ActorRef) extends ConnectionConfig
  case class ConnectTo(inetSocketAddress: InetSocketAddress) extends ConnectionConfig

  case class Config(
    owner: Option[ActorRef],
    connection: ConnectionConfig)

  def create(config: Config, actorName: Option[String] = None)(implicit actorRefFactory: ActorRefFactory): Api =
    actorName.fold {
      xmpp_stream.Api(actorRefFactory.actorOf(Props(classOf[Actor], config)))
    } { actorNameDefined =>
      xmpp_stream.Api(actorRefFactory.actorOf(Props(classOf[Actor], config), actorNameDefined))
    }
}
