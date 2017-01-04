package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream

import akka.actor.ActorRef
import com.github.rgafiyatullin.creek_xmpp.streams.{InputStream, OutputStream, StreamEvent}
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.transports.XmppTransport

import scala.collection.immutable.Queue
import scala.concurrent.Promise

final case class XmppStreamData(
  connection: ActorRef,
  transport: XmppTransport,
  outputStream: OutputStream = OutputStream.empty,
  inputStream: InputStream = InputStream.empty,
  enquiredStreamEvents: Queue[Promise[StreamEvent]] = Queue.empty,
  unprocessedStreamEvents: Queue[StreamEvent] = Queue.empty
) {
  def withTransport[T](f: XmppTransport => (T, XmppTransport)): (T, XmppStreamData) = {
    val (ret, transportNext) = f(transport)
    (ret, copy(transport = transportNext))
  }

  def withOutputStream[T](f: OutputStream => (T, OutputStream)): (T, XmppStreamData) = {
    val (ret, outputStreamNext) = f(outputStream)
    (ret, copy(outputStream = outputStreamNext))
  }

  def withInputStream[T](f: InputStream => (T, InputStream)): (T, XmppStreamData) = {
    val (ret, inputStreamNext) = f(inputStream)
    (ret, copy(inputStream = inputStreamNext))
  }

  def enquireStreamEvent(promise: Promise[StreamEvent]): XmppStreamData =
    (unprocessedStreamEvents.headOption match {
      case None =>
        copy(enquiredStreamEvents = enquiredStreamEvents.enqueue(promise))

      case Some(se) =>
        promise.success(se)
        copy(unprocessedStreamEvents = unprocessedStreamEvents.tail)
    }).assertStreamEventQueuesConsistency

  def appendStreamEvent(se: StreamEvent): XmppStreamData =
    (enquiredStreamEvents.headOption match {
      case None =>
        copy(unprocessedStreamEvents = unprocessedStreamEvents.enqueue(se))

      case Some(promise) =>
        promise.success(se)
        copy(enquiredStreamEvents = enquiredStreamEvents.tail)
    }).assertStreamEventQueuesConsistency


  private def assertStreamEventQueuesConsistency: XmppStreamData = {
    assert(enquiredStreamEvents.isEmpty || unprocessedStreamEvents.isEmpty)
    this
  }



}
