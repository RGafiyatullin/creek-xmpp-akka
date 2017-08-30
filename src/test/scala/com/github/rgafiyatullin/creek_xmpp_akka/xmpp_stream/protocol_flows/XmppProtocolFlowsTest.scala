package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows

import akka.util.Timeout
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.util.XmppServerUtil
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class XmppProtocolFlowsTest extends FlatSpec with Matchers with ScalaFutures with XmppServerUtil {
  implicit val timeout: Timeout = 5.seconds

  implicit val defaultPatience: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(500, Millis))

  implicit val executionContext: ExecutionContext = ExecutionContext.global

  "Basic test" should "succeed" in
    futureShouldSucceed(withClientAndServer { case (xmppServer, clientAndServer) =>
      val client = clientAndServer.client
      val server = clientAndServer.server
      for {
        _ <- client.expectConnected()
        _ <- server.expectConnected()
      }
        yield ()
    })
}
