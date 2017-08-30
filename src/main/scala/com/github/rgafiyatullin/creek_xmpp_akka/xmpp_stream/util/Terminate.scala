package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.util

import akka.actor.ActorRef
import akka.util.Timeout

import scala.concurrent.Future

object Terminate {
  final case class Query()
}

trait Terminate {
  import akka.pattern.ask
  val actorRef: ActorRef

  def terminate()(implicit timeout: Timeout): Future[Unit] =
    actorRef.ask(Terminate.Query()).mapTo[Unit]
}
