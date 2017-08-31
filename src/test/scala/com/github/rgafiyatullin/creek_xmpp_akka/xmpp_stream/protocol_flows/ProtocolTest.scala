package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows

import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.Protocol.ProcessResult
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Span}

import scala.concurrent.{ExecutionContext, Future}

object ProtocolTest {
  final case class AnError() extends Exception
}

class ProtocolTest extends FlatSpec with Matchers with ScalaFutures {
  import ProtocolTest.AnError

  implicit val defaultPatience: PatienceConfig =
    PatienceConfig(timeout = Span(100, Millis), interval = Span(10, Millis))

  implicit val executionContext: ExecutionContext = ExecutionContext.global

  val pIntToLong: Protocol[Int, Long] = new Protocol[Int, Long] {
    override protected def process[In1 <: Int]
      (context: Protocol.Context[In1])
      (implicit ec: ExecutionContext)
    : Future[ProcessResult[In1, Long]] =
      Future.successful(context.complete(context.value.toLong))
  }

  val pLongToString: Protocol[Long, String] = new Protocol[Long, String] {
    override protected def process[In1 <: Long]
      (context: Protocol.Context[In1])
      (implicit ec: ExecutionContext)
    : Future[ProcessResult[In1, String]]  =
      Future.successful(context.complete(context.value.toString))
  }

  val pLongToReject: Protocol[Long, Nothing] = Protocol.AlwaysReject()

  val pLongToFail: Protocol[Long, Nothing] = Protocol.AlwaysFail(AnError())


  "pIntToLong andThen (pLongToReject orElse pLongToString orElse pLongToFail)" should "convert an int into a string" in {
    val protocol = pIntToLong andThen (pLongToReject orElse pLongToString orElse pLongToFail)
    val initialContext = Protocol.Context.create(123)
    val result = protocol.run(initialContext)
    whenReady(result)(_.completeValueOption should contain ("123"))
  }

  "pIntToLong andThen (pLongToReject orElse pLongToFail orElse pLongToString)" should "fail" in {
    val protocol = pIntToLong andThen (pLongToReject orElse pLongToFail orElse pLongToString)
    val initialContext = Protocol.Context.create(123)
    val result = protocol.run(initialContext)
    whenReady(result)(_.failureOption should contain (AnError()))
  }

  "pIntToLong andThen pLongToReject" should "fail" in {
    val protocol = pIntToLong andThen pLongToReject
    val initialContext = Protocol.Context.create(123)
    val result = protocol.run(initialContext)
    whenReady(result)(_.failureOption should contain (Protocol.AndThen.RightBranchRejected()))
  }


}
