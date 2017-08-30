package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.util

import akka.actor.ActorRef
import akka.util.Timeout

import scala.concurrent.Future

object ExpectReady {
  final case class Query()
}

trait ExpectReady {
  import akka.pattern.ask

  val actorRef: ActorRef

  def expectReady()(implicit timeout: Timeout): Future[Unit] =
    actorRef.ask(ExpectReady.Query()).mapTo[Unit]
}
