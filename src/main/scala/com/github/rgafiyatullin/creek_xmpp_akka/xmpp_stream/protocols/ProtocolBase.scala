package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocols

import scala.collection.immutable.Queue
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.Try

object Protocol {
  trait Artifact

  object Context {
    final case class Internals(artifacts: Seq[Artifact] = Queue.empty[Artifact]) {
      def withArtifacts(a: Seq[Artifact]): Internals =
        copy(artifacts = a)
    }

    def create[A](value: A): Context[A] = Context(value, Internals())
  }

  final case class Context[+A](value: A, contextInternals: Context.Internals) {
    def withContextInternals(c: Context.Internals): Context[A] =
      copy(contextInternals = c)

    def artifacts[T <: Artifact](implicit classTag: ClassTag[T]): Seq[T] =
      contextInternals.artifacts.collect { case a: T => a }

    def addArtifact(a: Protocol.Artifact): Context[A] =
      withContextInternals(contextInternals.withArtifacts(contextInternals.artifacts :+ a))




    def fail(reason: Throwable): ProcessResult[Nothing, Nothing] =
      ProcessResult.Failed(reason)

    def reject(): ProcessResult[A, Nothing] =
      ProcessResult.Rejected(this)

    def complete[B](result: B): ProcessResult[Nothing, B] =
      ProcessResult.Complete(Context.create(result).withContextInternals(contextInternals))

    def complete: ProcessResult[Nothing, A] =
      ProcessResult.Complete(this)
  }

  sealed trait ProcessResult[+In, +Out] {
    def isComplete: Boolean
    def isRejected: Boolean
    def isFailure: Boolean

    def failureOption: Option[Throwable]
    def rejectedContextOption: Option[Context[In]]
    def completeContextOption: Option[Context[Out]]

    def rejectedValueOption: Option[In] = rejectedContextOption.map(_.value)
    def completeValueOption: Option[Out] = completeContextOption.map(_.value)
  }
  object ProcessResult {
    final case class Complete[+Out](context: Context[Out]) extends ProcessResult[Nothing, Out] {
      override def isComplete: Boolean = true
      override def isRejected: Boolean = false
      override def isFailure: Boolean = false

      override def failureOption: Option[Throwable] = None
      override def rejectedContextOption: Option[Context[Nothing]] = None
      override def completeContextOption: Option[Context[Out]] = Some(context)
    }
    final case class Rejected[+In](context: Context[In]) extends ProcessResult[In, Nothing] {
      override def isComplete: Boolean = false
      override def isRejected: Boolean = true
      override def isFailure: Boolean = false

      override def failureOption: Option[Throwable] = None
      override def rejectedContextOption: Option[Context[In]] = Some(context)
      override def completeContextOption: Option[Context[Nothing]] = None
    }

    final case class Failed(reason: Throwable) extends ProcessResult[Nothing, Nothing] {
      override def isComplete: Boolean = false
      override def isRejected: Boolean = false
      override def isFailure: Boolean = true

      override def failureOption: Option[Throwable] = Some(reason)
      override def rejectedContextOption: Option[Context[Nothing]] = None
      override def completeContextOption: Option[Context[Nothing]] = None
    }
  }


  final case class AlwaysComplete[Out](value: Out)
    extends ProtocolBase[Any, Out]
  {
    override protected def process[In1 <: Any]
      (context: Context[In1])
      (implicit ec: ExecutionContext)
    : Future[ProcessResult[In1, Out]] =
      Future.successful(context.complete(value))
  }

  final case class AlwaysReject[In]()
    extends ProtocolBase[In, Nothing]
  {
    override protected def process[In1 <: In]
      (context: Context[In1])
      (implicit ec: ExecutionContext)
    : Future[ProcessResult[In1, Nothing]] =
      Future.successful(context.reject())
  }

  final case class AlwaysFail(reason: Throwable)
    extends ProtocolBase[Any, Nothing]
  {
    override protected def process[In1 <: Any]
      (context: Context[In1])
      (implicit ec: ExecutionContext)
    : Future[ProcessResult[In1, Nothing]] =
      Future.successful(context.fail(reason))
  }

  final case class OrElse[In, Out]
    (left: ProtocolBase[In, Out], right: ProtocolBase[In, Out])
    extends ProtocolBase[In, Out]
  {
    override protected def process[In1 <: In]
      (context: Context[In1])
      (implicit ec: ExecutionContext)
    : Future[ProcessResult[In1, Out]] =
      for {
        result <- left.run(context)
        result <- result match {
          case complete: ProcessResult.Complete[Out] =>
            Future.successful(complete)

          case failed: ProcessResult.Failed =>
            Future.successful(failed)

          case rejected: ProcessResult.Rejected[In1] =>
            right.run(rejected.context)
        }
      } yield result
  }

  object AndThen {
    final case class RightBranchRejected() extends Exception
  }

  final case class AndThen[-In, Interim, +Out]
    (left: ProtocolBase[In, Interim], right: ProtocolBase[Interim, Out])
    extends ProtocolBase[In, Out]
  {
    override protected def process[In1 <: In]
      (context: Context[In1])
      (implicit ec: ExecutionContext)
    : Future[ProcessResult[In1, Out]] =
      for {
        leftResult <- left.run(context)
        rightResult <- leftResult match {
          case rejected: ProcessResult.Rejected[In1] =>
            Future.successful(rejected)

          case failed: ProcessResult.Failed =>
            Future.successful(failed)

          case complete: ProcessResult.Complete[Interim] =>
            for {
              rightResult <- right.run(complete.context)
            }
              yield rightResult match {
                case complete: ProcessResult.Complete[Out] =>
                  complete

                case failed: ProcessResult.Failed =>
                  failed

                case rejected: ProcessResult.Rejected[Interim] =>
                  rejected.context.fail(AndThen.RightBranchRejected())
              }
        }
      }
        yield rightResult
  }

  object Internals {
    def empty: Internals = Internals()
  }
  final case class Internals()
}

sealed trait ProtocolBase[-In, +Out] {
  import Protocol.{Context, ProcessResult}
  import Protocol.{OrElse, AndThen}

  protected def process[In1 <: In](context: Context[In1])(implicit ec: ExecutionContext): Future[ProcessResult[In1, Out]]

  def orElse[In1 <: In, Out1 >: Out](other: ProtocolBase[In1, Out1]): ProtocolBase[In1, Out1] =
    OrElse(this, other)

  def andThen[OutNext, Interim >: Out](other: ProtocolBase[Interim, OutNext]): ProtocolBase[In, OutNext] =
    AndThen[In, Interim, OutNext](this, other)

  private def recoverToResult[In1 <: In](context: Context[In1]): PartialFunction[Throwable, ProcessResult[In1, Out]] = {
    case e /*: Exception */ => context.fail(e)
  }

  private def recoverToResultFuture[In1 <: In](context: Context[In1]): PartialFunction[Throwable, Future[ProcessResult[In1, Out]]] = {
    case e /* : Exception */ => Future.successful(context.fail(e))
  }

  def run[In1 <: In](context: Context[In1])(implicit ec: ExecutionContext): Future[ProcessResult[In1, Out]] =
    Try {
      process(context)
        .recover(recoverToResult(context))
    }
      .recover(recoverToResultFuture(context))
      .get
}

trait Protocol[Self <: Protocol[Self, In, Out], -In, +Out] extends ProtocolBase[In, Out] {
  def protocolInternals: Protocol.Internals
  def withProtocolInternals(i: Protocol.Internals): Self
}

