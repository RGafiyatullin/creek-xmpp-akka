package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream

import akka.actor.ActorRef
import com.github.rgafiyatullin.creek_xmpp.streams.{InputStream, OutputStream, StreamEvent}
import com.github.rgafiyatullin.creek_xmpp_akka.XmppStream

import scala.collection.immutable.Queue

object Data {
  def create(config: XmppStream.Config, connection: ActorRef): Data = {
    val is = InputStream.empty
    val os = OutputStream.empty
    val utf8 = Utf8InputStream.empty

    Data(is, os, utf8, connection)
  }
}

case class Data(
  inputStream: InputStream,
  outputStream: OutputStream,
  utf8InputStream: Utf8InputStream,
  tcp: ActorRef,
  owner: Option[ActorRef] = None,
  inboundEvents: Queue[StreamEvent] = Queue.empty,
  inboundEventReplyTos: Queue[ActorRef] = Queue.empty)
