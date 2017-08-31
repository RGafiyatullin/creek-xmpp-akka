package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.xmpp

import akka.util.Timeout
import com.github.rgafiyatullin.creek_xml.common.QName
import com.github.rgafiyatullin.creek_xml.dom.Node
import com.github.rgafiyatullin.creek_xmpp.protocol.XmppConstants
import com.github.rgafiyatullin.creek_xmpp.protocol.stream_error.XmppStreamError
import com.github.rgafiyatullin.creek_xmpp.streams.StreamEvent
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.Protocol
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.Protocol.ProcessResult
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStream

import scala.concurrent.{ExecutionContext, Future}

object XmppClientProtocol {
  final case class StreamFeatures(node: Node) extends Protocol.Artifact {
    import com.github.rgafiyatullin.creek_xml.dom_query.Implicits._

    def featureOption(qName: QName): Option[Node] =
      (node select qName).headOption
  }
}

trait XmppClientProtocol extends Protocol[XmppStream, XmppStream] {
  type XmppContext = Protocol.Context[_ <: XmppStream]

  protected object features {
    import com.github.rgafiyatullin.creek_xml.dom_query.Implicits._

    def feature(context: XmppContext, qName: QName): Option[Node] =
      context
        .artifacts[XmppClientProtocol.StreamFeatures]
        .lastOption
        .flatMap(sf => (sf.node select qName).headOption)
  }

  protected object negotiation {
    def run[AnXmppStream <: XmppStream]
      (context: XmppContext, request: Node)
      (handle: PartialFunction[QName, Node => Future[ProcessResult[AnXmppStream, XmppStream]]])
      (implicit ec: ExecutionContext, timeout: Timeout)
    : Future[ProcessResult[AnXmppStream, XmppStream]] =
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
}
