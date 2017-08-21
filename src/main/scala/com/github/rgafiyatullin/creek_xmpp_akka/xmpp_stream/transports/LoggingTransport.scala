package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.transports
import akka.actor.Actor
import akka.event.LoggingAdapter
import akka.util.ByteString
import com.github.rgafiyatullin.creek_xml.common.HighLevelEvent
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.XmppStreamActor

object LoggingTransport {
  final case class Factory(xmppTransportFactory: XmppTransportFactory, loggingAdapter: LoggingAdapter) extends XmppTransportFactory {
    override def create: XmppTransport = LoggingTransport(xmppTransportFactory.create, loggingAdapter)
  }

  object implicits {
    implicit class XmppTransportFactoryWithLogging(xmppTransportFactory: XmppTransportFactory) {
      object logging {
        def enabled(loggingAdapter: LoggingAdapter): XmppTransportFactory =
          Factory(xmppTransportFactory.logging.disabled, loggingAdapter)

        def disabled: XmppTransportFactory =
          xmppTransportFactory match {
            case Factory(toUnwrap, _) => toUnwrap
            case toLeaveAsIs => toLeaveAsIs
          }
      }
    }
  }
}

final case class LoggingTransport(xmppTransport: XmppTransport, log: LoggingAdapter) extends XmppTransport {
  override def write(hles: Seq[HighLevelEvent]): (ByteString, XmppTransport) = {
    val (bytesOut, xmppTransportNext) = xmppTransport.write(hles)

    log.debug("[{}].write({}) -> {}", xmppTransport.name, hles, bytesOut.toArray.map(_ & 0xff).toSeq)

    (bytesOut, copy(xmppTransport = xmppTransportNext))
  }
  override def read(bytes: ByteString): (Seq[HighLevelEvent], XmppTransport) = {
    val (hles, xmppTransportNext) = xmppTransport.read(bytes)

    log.debug("[{}].read({}) -> {}", xmppTransport.name, bytes.toArray.map(_ & 0xff).toSeq, hles)

    (hles, copy(xmppTransport = xmppTransportNext))
  }

  override def name: String =
    xmppTransport.name

  override def reset: XmppTransport = {
    log.debug("[{}].reset", xmppTransport.name)
    copy(xmppTransport = xmppTransport.reset)
  }

  override def handover(previousTransport: XmppTransport, xmppStreamActor: XmppStreamActor, next: XmppTransport => Actor.Receive): Actor.Receive = {
    ???
  }
}


