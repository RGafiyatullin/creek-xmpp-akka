package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream

import akka.actor.{ActorRef, PoisonPill}
import akka.pattern.ask
import akka.util.Timeout
import com.github.rgafiyatullin.creek_xmpp.streams.StreamEvent
import com.github.rgafiyatullin.creek_xmpp_akka.common.actor_api

import scala.concurrent.Future

object Api {
  sealed trait Request

  case class SendEvent(streamEvent: StreamEvent)
    extends Request
  object SendEvent {
    type Result = actor_api.Result[Unit, SendError]
  }

  sealed trait SendError
  object SendError {
    case object TcpClosed extends SendError
  }



  case class RecvEvent(to: Option[ActorRef] = None) extends Request

  object RecvEvent {
    type Result = actor_api.Result[StreamEvent, RecvError]
  }

  sealed trait RecvError
  object RecvError {
    case object TcpClosed extends RecvError
  }

  case class Shutdown() extends Request
}

case class Api(xmppStreamActor: ActorRef) {
  def sendEvent(streamEvent: StreamEvent)(implicit timeout: Timeout): Future[Api.SendEvent.Result] =
    xmppStreamActor.ask(Api.SendEvent(streamEvent)).mapTo[Api.SendEvent.Result]

  def recvEvent()(implicit timeout: Timeout): Future[Api.RecvEvent.Result] =
    xmppStreamActor.ask(Api.RecvEvent()).mapTo[Api.RecvEvent.Result]

  def orderEvent(to: ActorRef): Unit =
    xmppStreamActor ! Api.RecvEvent(Some(to))

  def shutdown(): Unit =
    xmppStreamActor ! PoisonPill
}
