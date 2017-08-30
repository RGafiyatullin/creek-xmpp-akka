package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocols.xmpp.common
import akka.Done
import akka.stream.{Materializer, QueueOfferResult}
import akka.stream.scaladsl.{Flow, Keep, Sink, SourceQueue}
import akka.util.Timeout
import com.github.rgafiyatullin.creek_xmpp.streams.StreamEvent
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStream

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

object StanzasToStreamProtocol {
  final case class ProcessingStarted(
    promise: Promise[Unit] = Promise())
    extends XmppProtocol.Artifact

  sealed trait InboundStreamSetup
    extends XmppProtocol.Artifact
  {
    def connectToXmppStream
      (xmppStream: XmppStream)
      (implicit ec: ExecutionContext)
    : Future[Done]
  }

  sealed trait OutboundStreamSetup
    extends XmppProtocol.Artifact
  {
    def connectToXmppStream
    (xmppStream: XmppStream)
    (implicit ec: ExecutionContext)
    : Future[Done]
  }

  final case class InboundStreamSetupDrop()
    extends InboundStreamSetup
  {
    override def connectToXmppStream
      (xmppStream: XmppStream)
      (implicit ec: ExecutionContext)
    : Future[Done] = {
      loop(xmppStream)
      Promise[Done]().future
    }

    private def loop(xmppStream: XmppStream)(implicit ec: ExecutionContext): Unit =
      for {
        _ <- xmppStream.receiveStreamEvent()
      }
        loop(xmppStream)
  }

  final case class InboundStreamSetupSourceQueueFuture(
    sourceQueueFuture: Future[SourceQueue[StreamEvent]])
      extends InboundStreamSetup
  {
    override def connectToXmppStream(xmppStream: XmppStream)(implicit ec: ExecutionContext): Future[Done] = {
      val donePromise = Promise[Done]()
      for {
        sourceQueue <- sourceQueueFuture
        _ = loop(xmppStream, sourceQueue, donePromise)
        _ <- donePromise.future
      }
        yield Done
      donePromise.future
    }

    private def loop
      (xmppStream: XmppStream,
       sourceQueue: SourceQueue[StreamEvent],
       donePromise: Promise[Done])
      (implicit ec: ExecutionContext)
    : Unit =
      xmppStream.receiveStreamEvent()
        .flatMap { streamEvent =>
          sourceQueue.offer(streamEvent)
        }
        .map {
          case QueueOfferResult.Enqueued =>
            true
          case QueueOfferResult.Dropped =>
            throw new RuntimeException("Inbound stream event dropped")

          case QueueOfferResult.QueueClosed =>
            false

          case QueueOfferResult.Failure(reason) =>
            throw reason
        }
        .onComplete {
          case Success(true) =>
            loop(xmppStream, sourceQueue, donePromise)

          case Success(false) =>
            donePromise.success(Done)

          case Failure(reason) =>
            donePromise.failure(reason)
        }
  }

  final case class OutboundStreamSetupEmpty()
    extends OutboundStreamSetup
  {
    override def connectToXmppStream
      (xmppStream: XmppStream)
      (implicit ec: ExecutionContext)
    : Future[Done] =
      Future.successful(Done)
  }

  final case class OutboundStreamSetupSinkPromise(sinkPromise: Promise[Sink[StreamEvent, _]], sendTimeout: Timeout)
    extends OutboundStreamSetup
  {
    override def connectToXmppStream
      (xmppStream: XmppStream)
      (implicit ec: ExecutionContext)
    : Future[Done] = {
      val donePromise = Promise[Done]()
      val sink = Flow[StreamEvent]
        .mapAsync(1) { se =>
          val f = xmppStream.sendStreamEvent(se)(sendTimeout)
          f.onComplete(_ => if (se.isInstanceOf[StreamEvent.StreamClose]) donePromise.success(Done))
          f
        }.toMat(Sink.ignore)(Keep.right)

      sinkPromise.success(sink)
      donePromise.future
    }
  }

}

final case class StanzasToStreamProtocol(
  xmppProtocolInternals: XmppProtocol.Internals = XmppProtocol.Internals.empty)
    extends XmppProtocol[StanzasToStreamProtocol]
{
  import StanzasToStreamProtocol._

  override def withXmppProtocolInternals(b: XmppProtocol.Internals): StanzasToStreamProtocol =
    copy(xmppProtocolInternals = b)

  override protected def process[In1 <: XmppStream]
    (context: Ctx[In1])
    (implicit ec: ExecutionContext)
  : Future[Result[In1]] = {
    val xmppStream = context.value

    context
      .artifacts[ProcessingStarted]
      .foreach(_.promise.success(()))

    val inboundStreamSetup: InboundStreamSetup =
      context
        .artifacts[InboundStreamSetup]
        .headOption
        .getOrElse(InboundStreamSetupDrop())

    val outboundStreamSetup: OutboundStreamSetup =
      context
        .artifacts[OutboundStreamSetup]
        .headOption
        .getOrElse(OutboundStreamSetupEmpty())

    val outboundStreamDoneFuture = outboundStreamSetup.connectToXmppStream(xmppStream)
    val inboundStreamDoneFuture = inboundStreamSetup.connectToXmppStream(xmppStream)

    val bothStreamsDoneFuture = for {
      Done <- outboundStreamDoneFuture
      Done <- inboundStreamDoneFuture
    } yield Done

    bothStreamsDoneFuture
      .map { case Done => context.complete }
      .recover { case e: Exception => context.fail(e) }
  }
}
