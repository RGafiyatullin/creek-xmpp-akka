package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows

import scala.collection.immutable.Queue
import scala.reflect.ClassTag

object ProtocolContext {
  trait Artifact

  final case class Common[T](data: T, artifacts: Queue[Artifact] = Queue.empty) {
    def withXmppStream(d: T): Common[T] =
      copy(data = d)

    def addArtifact(artifact: Artifact): Common[T] =
      copy(artifacts = artifacts :+ artifact)

    def collectArtifacts[A <: Artifact](implicit classTag: ClassTag[A]): Seq[A] =
      artifacts.collect { case a: A => a }
  }



  def create[T](data: T): ProtocolContext[T] =
    Incomplete(Common(data))

  final case class Incomplete[T](common: Common[T]) extends ProtocolContext[T] {
    override def withCommon(c: Common[T]): ProtocolContext[T] =
      copy(common = c)


    override def failureOption: Option[Throwable] = None

    override def isReset: Boolean = true
    override def isRejected: Boolean = false
    override def isComplete: Boolean = false
    override def isFailure: Boolean = false
  }

  final case class Rejected[T](common: Common[T]) extends ProtocolContext[T] {
    override def withCommon(c: Common[T]): ProtocolContext[T] =
      copy(common = c)

    override def failureOption: Option[Throwable] = None

    override def isReset: Boolean = false
    override def isRejected: Boolean = true
    override def isComplete: Boolean = false
    override def isFailure: Boolean = false
  }

  final case class Complete[T](common: Common[T]) extends ProtocolContext[T] {
    override def withCommon(c: Common[T]): ProtocolContext[T] =
      copy(common = c)

    override def failureOption: Option[Throwable] = None

    override def isReset: Boolean = false
    override def isRejected: Boolean = false
    override def isComplete: Boolean = true
    override def isFailure: Boolean = false
  }

  final case class Failure[T](common: Common[T], reason: Throwable) extends ProtocolContext[T] {
    override def withCommon(c: Common[T]): ProtocolContext[T] =
      copy(common = c)

    override def failureOption: Option[Throwable] = Some(reason)

    override def isReset: Boolean = false
    override def isRejected: Boolean = false
    override def isComplete: Boolean = false
    override def isFailure: Boolean = true
  }
}

sealed trait ProtocolContext[T] {
  import ProtocolContext.Artifact


  def common: ProtocolContext.Common[T]
  def withCommon(c: ProtocolContext.Common[T]): ProtocolContext[T]



  def artifacts[A <: Artifact](implicit classTag: ClassTag[A]): Seq[A] =
    common.collectArtifacts[A]

  def addArtifact(a: Artifact): ProtocolContext[T] =
    withCommon(common.addArtifact(a))

  def failureOption: Option[Throwable]

  def isReset: Boolean
  def isRejected: Boolean
  def isComplete: Boolean
  def isFailure: Boolean

  def reset: ProtocolContext[T] =
    ProtocolContext.Incomplete(common)

  def reject: ProtocolContext[T] =
    ProtocolContext.Rejected(common)

  def complete: ProtocolContext[T] =
    ProtocolContext.Complete(common)

  def failure(reason: Throwable): ProtocolContext[T] =
    ProtocolContext.Failure(common, reason)
}
