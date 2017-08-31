package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.xmpp

import java.nio.charset.Charset

import akka.util.Timeout
import com.github.rgafiyatullin.creek_xml.common.QName
import com.github.rgafiyatullin.creek_xml.dom.{CData, Element, Node}
import com.github.rgafiyatullin.creek_xmpp.protocol.XmppConstants
import com.github.rgafiyatullin.creek_xmpp.protocol.stream_error.XmppStreamError
import com.github.rgafiyatullin.creek_xmpp.streams.StreamEvent
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.Protocol
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.Protocol.ProcessResult
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStream

import scala.concurrent.{ExecutionContext, Future}

object SaslAuth {
  val csUtf8: Charset = Charset.forName("UTF-8")

  object names {
    val ns: String = "urn:ietf:params:xml:ns:xmpp-sasl"
    val qNameSaslMechanismsFeature: QName = QName(ns, "mechanisms")
    val qNameSaslMechanism: QName = QName(ns, "mechanism")
    val qNameAuth: QName = QName(ns, "auth")
    val qNameFailure: QName = QName(ns, "failure")
    val qNameSuccess: QName = QName(ns, "success")

    object mechanisms {
      val plain = "PLAIN"
    }
  }


  trait SaslAuthenticationClientProtocol extends XmppClientProtocol {
    def hasSaslMechanism(context: Protocol.Context[XmppStream], mechanism: String): Option[Node] =
      features.feature(context, names.qNameSaslMechanismsFeature)
        .flatMap(_.children.collectFirst { case node: Node if node.text == mechanism => node })
  }

  final case class PlainText
    (username: String, password: String)
    (implicit timeout: Timeout)
      extends SaslAuthenticationClientProtocol
  {
    def withUsername(u: String): PlainText = copy(username = u)
    def withPassword(p: String): PlainText = copy(password = p)

    private def hasFeature(context: Protocol.Context[XmppStream]): Boolean =
      hasSaslMechanism(context, names.mechanisms.plain).isDefined

    private def ifHasFeature[In1 <: XmppStream]
      (context: Protocol.Context[In1])
      (doIt: => Future[ProcessResult[In1, XmppStream]])
    : Future[ProcessResult[In1, XmppStream]] =
      if (!hasFeature(context))
        Future.successful(context.reject())
      else
        doIt

    private def authenticateCData: Node = {
      val zero = Array[Byte](0)
      val usernameBytes = username.getBytes(csUtf8)
      val passwordBytes = password.getBytes(csUtf8)

      val packed = zero ++ usernameBytes ++ zero ++ passwordBytes
      CData(new String(packed, csUtf8))
    }
    private def authenticateRequest: Node = Element(names.qNameAuth, Seq(), Seq(authenticateCData))

    override protected def process[In1 <: XmppStream]
      (context: Protocol.Context[In1])
      (implicit ec: ExecutionContext)
    : Future[Protocol.ProcessResult[In1, XmppStream]] = {
      val xmppStream = context.value

      ifHasFeature(context)(negotiation.run(context, authenticateRequest) {
        case names.qNameSuccess => (_) =>
          XmppClientStreamOpen(Seq.empty).run(context)

        case names.qNameFailure => (failureResponseXml) =>
          Future.successful(
            context.fail(
              XmppStreamError.NotAuthorized()
                .withText("SASL authentication failure: %s".format(failureResponseXml.text))))
      })
    }
  }
}
