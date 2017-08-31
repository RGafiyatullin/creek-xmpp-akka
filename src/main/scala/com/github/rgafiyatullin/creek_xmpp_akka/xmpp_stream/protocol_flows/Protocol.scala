package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows

import scala.collection.immutable.Queue
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.Try

object Protocol {
  trait Artifact

  final case class Common(artifacts: Seq[Artifact] = Queue.empty[Artifact]) {
    def withArtifacts(a: Seq[Artifact]): Common =
      copy(artifacts = a)
  }

  object Context {
    def create[A](value: A): Context[A] = Context(value, Common())
  }

  final case class Context[+A](value: A, common: Common) {

    def artifacts[T <: Artifact](implicit classTag: ClassTag[T]): Seq[T] =
      common.artifacts.collect { case a: T => a }

    def fail(reason: Throwable): ProcessResult[Nothing, Nothing] =
      ProcessResult.Failed(reason)

    def reject(): ProcessResult[A, Nothing] =
      ProcessResult.Rejected(this)

    def complete[B](result: B): ProcessResult[Nothing, B] =
      ProcessResult.Complete(Context.create(result).withCommon(common))

    def complete: ProcessResult[Nothing, A] =
      ProcessResult.Complete(this)

    def withCommon(c: Common): Context[A] =
      copy(common = c)

    def addArtifact(a: Protocol.Artifact): Context[A] =
      withCommon(common.withArtifacts(common.artifacts :+ a))
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
    extends Protocol[Any, Out]
  {
    override protected def process[In1 <: Any]
      (context: Context[In1])
      (implicit ec: ExecutionContext)
    : Future[ProcessResult[In1, Out]] =
      Future.successful(context.complete(value))
  }

  final case class AlwaysReject[In]()
    extends Protocol[In, Nothing]
  {
    override protected def process[In1 <: In]
      (context: Context[In1])
      (implicit ec: ExecutionContext)
    : Future[ProcessResult[In1, Nothing]] =
      Future.successful(context.reject())
  }

  final case class AlwaysFail(reason: Throwable)
    extends Protocol[Any, Nothing]
  {
    override protected def process[In1 <: Any]
      (context: Context[In1])
      (implicit ec: ExecutionContext)
    : Future[ProcessResult[In1, Nothing]] =
      Future.successful(context.fail(reason))
  }

  final case class OrElse[In, Out]
    (left: Protocol[In, Out], right: Protocol[In, Out])
    extends Protocol[In, Out]
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
    (left: Protocol[In, Interim], right: Protocol[Interim, Out])
    extends Protocol[In, Out]
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
}

trait Protocol[-In, +Out] {
  import Protocol.{Context, ProcessResult}
  import Protocol.{OrElse, AndThen}

  protected def process[In1 <: In](context: Context[In1])(implicit ec: ExecutionContext): Future[ProcessResult[In1, Out]]

  def orElse[In1 <: In, Out1 >: Out](other: Protocol[In1, Out1]): Protocol[In1, Out1] =
    OrElse(this, other)

  def andThen[OutNext, Interim >: Out](other: Protocol[Interim, OutNext]): Protocol[In, OutNext] =
    AndThen[In, Interim, OutNext](this, other)

  private def recoverToResult[In1 <: In](context: Context[In1]): PartialFunction[Throwable, ProcessResult[In1, Out]] = {
    case e: Exception => context.fail(e)
  }

  private def recoverToResultFuture[In1 <: In](context: Context[In1]): PartialFunction[Throwable, Future[ProcessResult[In1, Out]]] = {
    case e: Exception => Future.successful(context.fail(e))
  }

  def run[In1 <: In](context: Context[In1])(implicit ec: ExecutionContext): Future[ProcessResult[In1, Out]] =
    Try {
      process(context)
        .recover(recoverToResult(context))
    }
      .recover(recoverToResultFuture(context))
      .get

}
