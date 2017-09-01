package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows

import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows.Protocol.ProcessResult
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Span}

import scala.concurrent.{ExecutionContext, Future}

object ProtocolTest {
  final case class AnError() extends Exception
  final case class ProtocolIntToLong() extends Protocol[ProtocolIntToLong, Int, Long] {
    override def protocolInternals: Protocol.Internals = Protocol.Internals.empty
    override def withProtocolInternals(i: Protocol.Internals): ProtocolIntToLong = this

    override protected def process[In1 <: Int](context: Protocol.Context[In1])(implicit ec: ExecutionContext): Future[ProcessResult[In1, Long]] =
      Future.successful(context.complete(context.value.toLong))
  }

  final case class ProtocolLongToString() extends Protocol[ProtocolLongToString, Long, String] {
    override def protocolInternals: Protocol.Internals = Protocol.Internals.empty
    override def withProtocolInternals(i: Protocol.Internals): ProtocolLongToString = this

    override protected def process[In1 <: Long](context: Protocol.Context[In1])(implicit ec: ExecutionContext): Future[ProcessResult[In1, String]] =
      Future.successful(context.complete(context.value.toString))
  }
}

class ProtocolTest extends FlatSpec with Matchers with ScalaFutures {
  import ProtocolTest._

  implicit val defaultPatience: PatienceConfig =
    PatienceConfig(timeout = Span(100, Millis), interval = Span(10, Millis))

  implicit val executionContext: ExecutionContext = ExecutionContext.global

  val pLongToReject: ProtocolBase[Long, Nothing] = Protocol.AlwaysReject()

  val pLongToFail: ProtocolBase[Long, Nothing] = Protocol.AlwaysFail(AnError())


  "pIntToLong andThen (pLongToReject orElse pLongToString orElse pLongToFail)" should "convert an int into a string" in {
    val protocol = ProtocolIntToLong() andThen (pLongToReject orElse ProtocolLongToString() orElse pLongToFail)
    val initialContext = Protocol.Context.create(123)
    val result = protocol.run(initialContext)
    whenReady(result)(_.completeValueOption should contain ("123"))
  }

  "pIntToLong andThen (pLongToReject orElse pLongToFail orElse pLongToString)" should "fail" in {
    val protocol = ProtocolIntToLong() andThen (pLongToReject orElse pLongToFail orElse ProtocolLongToString())
    val initialContext = Protocol.Context.create(123)
    val result = protocol.run(initialContext)
    whenReady(result)(_.failureOption should contain (AnError()))
  }

  "pIntToLong andThen pLongToReject" should "fail" in {
    val protocol = ProtocolIntToLong() andThen pLongToReject
    val initialContext = Protocol.Context.create(123)
    val result = protocol.run(initialContext)
    whenReady(result)(_.failureOption should contain (Protocol.AndThen.RightBranchRejected()))
  }


}
