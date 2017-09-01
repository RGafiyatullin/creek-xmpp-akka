package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.xmpp.client

import akka.util.Timeout
import com.github.rgafiyatullin.creek_xml.common.QName
import com.github.rgafiyatullin.creek_xml.dom.Node
import com.github.rgafiyatullin.creek_xmpp.protocol.XmppConstants
import com.github.rgafiyatullin.creek_xmpp.protocol.stream_error.XmppStreamError
import com.github.rgafiyatullin.creek_xmpp.streams.StreamEvent
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.Protocol
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.xmpp.common.XmppProtocol
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStream
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.transports.XmppTransportFactory

import scala.concurrent.{ExecutionContext, Future}

object XmppClientProtocol {
  trait Artifact extends Protocol.Artifact

  object Internals {
    def empty: Internals = Internals()
  }
  final case class Internals(xmppProtocolInternals: XmppProtocol.Internals = XmppProtocol.Internals.empty) {
    def withXmppProtocolInternals(b: XmppProtocol.Internals): XmppClientProtocol.Internals =
      copy(xmppProtocolInternals = b)
  }

  final case class StreamFeatures(node: Node) extends XmppClientProtocol.Artifact {
    import com.github.rgafiyatullin.creek_xml.dom_query.Implicits._

    def featureOption(qName: QName): Option[Node] =
      (node select qName).headOption
  }

  case class AlwaysComplete(xmppClientProtocolInternals: Internals = Internals.empty) extends XmppClientProtocol[AlwaysComplete] {
    override def withXmppClientProtocolInternals(b: Internals): AlwaysComplete = copy(xmppClientProtocolInternals = b)

    override protected def process[In1 <: XmppStream]
      (context: Protocol.Context[In1])
      (implicit ec: ExecutionContext)
    : Future[Protocol.ProcessResult[In1, XmppStream]] =
      Future.successful(context.complete)
  }

  case class FailWithStreamError(xmppStreamError: XmppStreamError, xmppClientProtocolInternals: Internals = Internals.empty) extends XmppClientProtocol[FailWithStreamError] {
    override def withXmppClientProtocolInternals(b: Internals): FailWithStreamError = copy(xmppClientProtocolInternals = b)

    override protected def process[In1 <: XmppStream]
      (context: Protocol.Context[In1])
      (implicit ec: ExecutionContext)
    : Future[Protocol.ProcessResult[In1, XmppStream]] =
      Future.successful(context.fail(xmppStreamError))
  }
}

trait XmppClientProtocol[Self <: XmppClientProtocol[Self]] extends XmppProtocol[Self] {
  import XmppClientProtocol.Internals

  override def xmppProtocolInternals: XmppProtocol.Internals = xmppClientProtocolInternals.xmppProtocolInternals
  override def withXmppProtocolInternals(b: XmppProtocol.Internals): Self =
    withXmppClientProtocolInternals(xmppClientProtocolInternals.withXmppProtocolInternals(b))

  def xmppClientProtocolInternals: Internals
  def withXmppClientProtocolInternals(b: Internals): Self




  protected object features {
    import com.github.rgafiyatullin.creek_xml.dom_query.Implicits._

    def feature[AnXmppStream <: XmppStream](context: Ctx[AnXmppStream], qName: QName): Option[Node] =
      context
        .artifacts[XmppClientProtocol.StreamFeatures]
        .lastOption
        .flatMap(sf => (sf.node select qName).headOption)
  }

  protected object negotiation {
    def run[AnXmppStream <: XmppStream]
      (context: Ctx[AnXmppStream], request: Node)
      (handle: PartialFunction[QName, Node => Future[Protocol.ProcessResult[AnXmppStream, XmppStream]]])
      (implicit ec: ExecutionContext, timeout: Timeout)
    : Future[Protocol.ProcessResult[AnXmppStream, XmppStream]] =
      for {
        _ <- context.value.sendStreamEvent(StreamEvent.Stanza(request))
        StreamEvent.Stanza(responseXml) <- context.value.receiveStreamEvent()

        result <-
          if (handle.isDefinedAt(responseXml.qName))
            handle(responseXml.qName)(responseXml)
          else if (responseXml.qName == XmppConstants.names.streams.error)
            Future.successful(
              context.fail(
                XmppStreamError
                  .fromStanza(responseXml)
                  .getOrElse(
                    XmppStreamError
                      .InternalServerError()
                      .withText("Failed to parse stream-error"))))
          else
            Future.successful(
              context.fail(XmppStreamError
                .UnsupportedFeature()
                .withText("Unexpected response for %s request".format(request.qName))))
      }
        yield result
  }

  protected object streams {
    def reopen[AnXmppStream <: XmppStream]
    (context: Ctx[AnXmppStream], transportFactoryOption: Option[XmppTransportFactory] = None)
    (implicit ec: ExecutionContext, timeout: Timeout)
    : Future[Protocol.ProcessResult[AnXmppStream, XmppStream]] = {
      val attributesToUse =
        context
          .artifacts[XmppClientStreamOpen.LocalStreamAttributes]
          .lastOption
          .map(_.attributes)
          .getOrElse(Seq.empty)
      XmppClientStreamOpen(attributesToUse, transportFactoryOption).run(context)
    }

  }
}
