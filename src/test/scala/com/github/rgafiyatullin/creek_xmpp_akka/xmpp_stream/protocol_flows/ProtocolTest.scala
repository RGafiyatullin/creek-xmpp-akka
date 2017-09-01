package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows

import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.ProtocolBase.ProcessResult
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

  val pIntToLong: ProtocolBase[Int, Long] = new ProtocolBase[Int, Long] {
    override protected def process[In1 <: Int]
      (context: ProtocolBase.Context[In1])
      (implicit ec: ExecutionContext)
    : Future[ProcessResult[In1, Long]] =
      Future.successful(context.complete(context.value.toLong))
  }

  val pLongToString: ProtocolBase[Long, String] = new ProtocolBase[Long, String] {
    override protected def process[In1 <: Long]
      (context: ProtocolBase.Context[In1])
      (implicit ec: ExecutionContext)
    : Future[ProcessResult[In1, String]]  =
      Future.successful(context.complete(context.value.toString))
  }

  val pLongToReject: ProtocolBase[Long, Nothing] = ProtocolBase.AlwaysReject()

  val pLongToFail: ProtocolBase[Long, Nothing] = ProtocolBase.AlwaysFail(AnError())


  "pIntToLong andThen (pLongToReject orElse pLongToString orElse pLongToFail)" should "convert an int into a string" in {
    val protocol = pIntToLong andThen (pLongToReject orElse pLongToString orElse pLongToFail)
    val initialContext = ProtocolBase.Context.create(123)
    val result = protocol.run(initialContext)
    whenReady(result)(_.completeValueOption should contain ("123"))
  }

  "pIntToLong andThen (pLongToReject orElse pLongToFail orElse pLongToString)" should "fail" in {
    val protocol = pIntToLong andThen (pLongToReject orElse pLongToFail orElse pLongToString)
    val initialContext = ProtocolBase.Context.create(123)
    val result = protocol.run(initialContext)
    whenReady(result)(_.failureOption should contain (AnError()))
  }

  "pIntToLong andThen pLongToReject" should "fail" in {
    val protocol = pIntToLong andThen pLongToReject
    val initialContext = ProtocolBase.Context.create(123)
    val result = protocol.run(initialContext)
    whenReady(result)(_.failureOption should contain (ProtocolBase.AndThen.RightBranchRejected()))
  }


}
