package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props, Terminated}
import akka.util.Timeout
import com.github.rgafiyatullin.owl_akka_goodies.actor_future.ActorStdReceive

import scala.concurrent.ExecutionContext

object XmppStreamOwnerWatchSat {
  def props(xmppStreamActorRef: ActorRef, timeout: Timeout): Props =
    Props(classOf[XmppStreamOwnerWatchSatActor], xmppStreamActorRef, timeout)

  final class XmppStreamOwnerWatchSatActor(xmppStreamActorRef: ActorRef, timeout: Timeout)
    extends Actor
      with ActorLogging
      with ActorStdReceive
  {
    implicit val executionContext: ExecutionContext = context.dispatcher
    val xmppStream = XmppStream(xmppStreamActorRef)

    val timeoutCancellable: Cancellable = context.system.scheduler.scheduleOnce(
      timeout.duration, self, XmppStream.api.RegisterOwner.RegisterTimeout)

    override def receive: Receive =
      handleTimeout() orElse
        handleRegister() orElse
          stdReceive.discard


    def handleTimeout(): Receive = {
      case XmppStream.api.RegisterOwner.RegisterTimeout =>
        log.warning("No owner registered within {}: shutting down", timeout)
        xmppStream.terminate()(Timeout.zero)
        context become stdReceive.discard
    }

    def handleRegister(): Receive = {
      case XmppStream.api.RegisterOwner(owner) =>
        log.debug("Owner registered: {}", owner)
        timeoutCancellable.cancel()
        context watch owner
        context become whenReady(owner)
    }

    def handleTerminated(owner: ActorRef): Receive = {
      case Terminated(`owner`) =>
        log.debug("Owner terminated. Shutting down. [owner: {}]", owner)
        context stop self
        context become stdReceive.discard
    }

    def handleRegister(oldOwner: ActorRef): Receive = {
      case XmppStream.api.RegisterOwner(newOwner) =>
        log.debug("Owner changed. [new: {}; old: {}]", newOwner, oldOwner)
        context unwatch oldOwner
        context watch newOwner
        context become whenReady(newOwner)
    }

    def whenReady(owner: ActorRef): Receive =
      handleTerminated(owner) orElse
        handleRegister(owner) orElse
        stdReceive.discard
  }
}
