package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.{ExecutionContext, Future}

object PresetProtocolsTest {
  final case class AnError() extends Exception
}

class PresetProtocolsTest extends FlatSpec with Matchers with ScalaFutures {
  import PresetProtocolsTest.AnError
  import Protocol.{AlwaysSuccess, AlwaysReject, AlwaysFailure}

  implicit val executionContext: ExecutionContext = ExecutionContext.global

  val alwaysSuccess: Protocol[Unit] = AlwaysSuccess[Unit]()
  val alwaysFailure: Protocol[Unit] = AlwaysFailure[Unit](AnError())
  val alwaysReject : Protocol[Unit] = AlwaysReject[Unit]()

  val initialProtocolContext: ProtocolContext[Unit] = ProtocolContext.create(())

  def withProtocol[T](p: Protocol[Unit])(f: ProtocolContext[Unit] => T): Future[T] =
    for {
      ctx <- p.process(initialProtocolContext)
    }
      yield f(ctx)

  "AlwaysSuccess" should "always succeed" in
    withProtocol(alwaysSuccess)(_.isComplete should be (true))

  "AlwaysFailure" should "always fail" in
    withProtocol(alwaysFailure)(_.isFailure should be (true))

  "AlwaysReject" should "always reject" in
    withProtocol(alwaysReject)(_.isRejected should be (true))

  /**
    * S -> Success
    * F -> Failure
    * R -> Reject
    * OE -> OrElse
    * AT -> AndThen
    *
    * For binary ops we need to ensure the following cases:
    * - S, S => OE|S AT|S
    * - S, F => OE|S AT|F
    * - S, R => OE|S AT|R
    * - F, S => OE|F AT|F
    * - F, F => OE|F AT|F
    * - F, R => OE|F AT|F
    * - R, S => OE|S AT|R
    * - R, F => OE|F AT|F
    * - R, R => OE|R AT|R
    *
    * I.e.
    * - OE(S, _) => S
    * - OE(R, X) => X
    * - OE(F, _) => F
    *
    * - AT(S, S) => S
    * - AT(F, _) => F
    * - AT(R, X) => R
    * */

  val tripleTrue: Seq[Boolean] = Seq(true, true, true)

  "OrElse" should "OrElse(S, _) => S" in
    whenReady(Future.sequence(Seq(
      withProtocol(alwaysSuccess orElse alwaysSuccess)(_.isComplete),
      withProtocol(alwaysSuccess orElse alwaysReject)(_.isComplete),
      withProtocol(alwaysSuccess orElse alwaysFailure)(_.isComplete)
    )))(_ should be (tripleTrue))

  it should "OrElse(R, X) => X" in
    whenReady(Future.sequence(Seq(
      withProtocol(alwaysReject orElse alwaysSuccess)(_.isComplete),
      withProtocol(alwaysReject orElse alwaysReject)(_.isRejected),
      withProtocol(alwaysReject orElse alwaysFailure)(_.isFailure)
    )))(_ should be (tripleTrue))

  it should "OrElse(F, _) => F" in
    whenReady(Future.sequence(Seq(
      withProtocol(alwaysFailure orElse alwaysSuccess)(_.isFailure),
      withProtocol(alwaysFailure orElse alwaysReject)(_.isFailure),
      withProtocol(alwaysFailure orElse alwaysFailure)(_.isFailure)
    )))(_ should be (tripleTrue))

  "AndThen" should "AndThen(S, S) => S" in
    whenReady(withProtocol(alwaysSuccess andThen alwaysSuccess)(_.isComplete))(_ should be (true))

  it should "AndThen(F, _) => F" in
    whenReady(Future.sequence(Seq(
      withProtocol(alwaysFailure andThen alwaysSuccess)(_.isFailure),
      withProtocol(alwaysFailure andThen alwaysReject)(_.isFailure),
      withProtocol(alwaysFailure andThen alwaysFailure)(_.isFailure)
    )))(_ should be (tripleTrue))

  it should "AndThen(R, _) => R" in
    whenReady(Future.sequence(Seq(
      withProtocol(alwaysReject andThen alwaysSuccess)(_.isRejected),
      withProtocol(alwaysReject andThen alwaysReject)(_.isRejected),
      withProtocol(alwaysReject andThen alwaysFailure)(_.isRejected)
    )))(_ should be (tripleTrue))

  "Any protocol" should "fail in case non-reset context is passed into it" in {
    whenReady(alwaysReject.process(initialProtocolContext.reject))(_.failureOption.exists(_.isInstanceOf[Protocol.errors.ContextWasntReset[_]]) should be (true))
    whenReady(alwaysSuccess.process(initialProtocolContext.reject))(_.failureOption.exists(_.isInstanceOf[Protocol.errors.ContextWasntReset[_]]) should be (true))
    whenReady(alwaysFailure.process(initialProtocolContext.reject))(_.failureOption.exists(_.isInstanceOf[Protocol.errors.ContextWasntReset[_]]) should be (true))

    whenReady(alwaysReject.process(initialProtocolContext.complete))(_.failureOption.exists(_.isInstanceOf[Protocol.errors.ContextWasntReset[_]]) should be (true))
    whenReady(alwaysSuccess.process(initialProtocolContext.complete))(_.failureOption.exists(_.isInstanceOf[Protocol.errors.ContextWasntReset[_]]) should be (true))
    whenReady(alwaysFailure.process(initialProtocolContext.complete))(_.failureOption.exists(_.isInstanceOf[Protocol.errors.ContextWasntReset[_]]) should be (true))

    whenReady(alwaysReject.process(initialProtocolContext.failure(AnError())))(_.failureOption.exists(_.isInstanceOf[Protocol.errors.ContextWasntReset[_]]) should be (true))
    whenReady(alwaysSuccess.process(initialProtocolContext.failure(AnError())))(_.failureOption.exists(_.isInstanceOf[Protocol.errors.ContextWasntReset[_]]) should be (true))
    whenReady(alwaysFailure.process(initialProtocolContext.failure(AnError())))(_.failureOption.exists(_.isInstanceOf[Protocol.errors.ContextWasntReset[_]]) should be (true))
  }

}
