package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocol_flows_0

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object Protocol {
  object errors {
    sealed trait ProtocolFlowError extends Exception
    final case class ContextLeftUntouched[T](context: ProtocolContext[T]) extends ProtocolFlowError {
      override def getMessage: String =
        "Protocol context left untouched after processing"
    }

    case class ContextWasntReset[T](context: ProtocolContext[T]) extends ProtocolFlowError {
      override def getMessage: String =
        "Expected input context to be an instance of ProtocolContext.Incomplete. Actual: %s".format(context)
    }

  }

  final case class AlwaysSuccess[T]() extends Protocol[T] {
    override protected def doProcess
      (context: ProtocolContext[T])
      (implicit executionContext: ExecutionContext)
    : Future[ProtocolContext[T]] =
      Future.successful(context.complete)

  }

  final case class AlwaysFailure[T](reason: Throwable) extends Protocol[T] {
    override protected def doProcess
      (context: ProtocolContext[T])
      (implicit executionContext: ExecutionContext)
    : Future[ProtocolContext[T]] =
      Future.successful(context.failure(reason))
  }

  final case class AlwaysReject[T]() extends Protocol[T] {
    override protected def doProcess
      (context: ProtocolContext[T])
      (implicit executionContext: ExecutionContext)
    : Future[ProtocolContext[T]] =
      Future.successful(context.reject)
  }

  final case class AndThen[T](left: Protocol[T], right: Protocol[T]) extends Protocol[T] {
    override protected def doProcess
      (context: ProtocolContext[T])
      (implicit executionContext: ExecutionContext)
    : Future[ProtocolContext[T]] =
      for {
        context <- left.process(context)
        context <-
          if (context.isComplete) right.process(context.reset)
          else Future.successful(context)
      }
        yield context
  }

  final case class OrElse[T](left: Protocol[T], right: Protocol[T]) extends Protocol[T] {
    override protected def doProcess
      (context: ProtocolContext[T])
      (implicit executionContext: ExecutionContext)
    : Future[ProtocolContext[T]] =
      for {
        context <- left.process(context)
        context <-
          if (context.isComplete)
            Future.successful(context)
          else if (context.isRejected)
            right.process(context.reset)
          else
            Future.successful(context)
      }
        yield context
  }
}

trait Protocol[T] {
  protected def inputProtocolContextIsNotIncomplete(pec: ProtocolContext[T]): Throwable =
    Protocol.errors.ContextWasntReset(pec)

  protected def doProcess(context: ProtocolContext[T])(implicit executionContext: ExecutionContext): Future[ProtocolContext[T]]

  private def recoverExceptionToFailedFuture(context: ProtocolContext[T]): PartialFunction[Throwable, Future[ProtocolContext[T]]] = {
    case reason: Exception =>
      Future.successful(context.failure(reason))
  }

  private def failedFutureToFailedContext(context: ProtocolContext[T]): PartialFunction[Throwable, ProtocolContext[T]] = {
    case reason: Exception =>
      context.failure(reason)
  }

  def process
    (context: ProtocolContext[T])
    (implicit executionContext: ExecutionContext)
  : Future[ProtocolContext[T]] =
    Try {
      if (!context.isReset)
        throw inputProtocolContextIsNotIncomplete(context)

      doProcess(context)
        .map(ctx =>
          if (ctx.isReset)
            ctx.failure(Protocol.errors.ContextLeftUntouched(ctx))
          else
            ctx)
        .recover(failedFutureToFailedContext(context))
    }
      .recover(recoverExceptionToFailedFuture(context))
      .get

  def orElse(fallback: Protocol[T]): Protocol[T] =
    Protocol.OrElse(this, fallback)

  def andThen(next: Protocol[T]): Protocol[T] =
    Protocol.AndThen(this, next)
}
