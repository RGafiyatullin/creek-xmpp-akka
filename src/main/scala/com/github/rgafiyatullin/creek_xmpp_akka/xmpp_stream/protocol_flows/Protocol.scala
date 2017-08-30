package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows

import scala.concurrent.Future

object Protocol {
  final case class AndThen(left: Protocol, right: Protocol) extends Protocol {
    override def process(context: ProtocolExecutionContext): Future[ProtocolExecutionContext] = ???
  }

  final case class OrElse(left: Protocol, right: Protocol) extends Protocol {
    override def process(context: ProtocolExecutionContext): Future[ProtocolExecutionContext] = ???
  }
}

trait Protocol {
  def process(context: ProtocolExecutionContext): Future[ProtocolExecutionContext]

  def orElse(fallback: Protocol): Protocol =
    Protocol.OrElse(this, fallback)

  def andThen(next: Protocol): Protocol =
    Protocol.AndThen(this, next)
}
