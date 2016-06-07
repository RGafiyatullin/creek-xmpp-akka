package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream

import akka.actor.ActorRef
import com.github.rgafiyatullin.creek_xmpp.streams.{InputStream, OutputStream, StreamEvent}
import com.github.rgafiyatullin.creek_xmpp_akka.XmppStream

import scala.collection.immutable.Queue

object Data {
  def create(config: XmppStream.Config, connection: ActorRef): Data = {
    val is = InputStream.empty
    val os = OutputStream.empty

    Data(
      is, os, connection, config.owner,
      Queue.empty, Queue.empty)
  }
}

case class Data(
                 inputStream: InputStream,
                 outputStream: OutputStream,
                 tcp: ActorRef,
                 owner: Option[ActorRef],
                 inboundEvents: Queue[StreamEvent],
                 inboundEventReplyTos: Queue[ActorRef]
               )
{

}
