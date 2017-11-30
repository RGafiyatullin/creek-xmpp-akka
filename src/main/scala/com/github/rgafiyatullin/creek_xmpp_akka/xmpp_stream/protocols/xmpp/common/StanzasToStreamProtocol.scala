package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.protocols.xmpp.common
import akka.Done
import akka.stream.{Materializer, QueueOfferResult}
import akka.stream.scaladsl.{Flow, Keep, Sink, SinkQueue, SourceQueue, SourceQueueWithComplete}
import akka.util.Timeout
import com.github.rgafiyatullin.creek_xmpp.streams.StreamEvent
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStream

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

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
    sourceQueueFuture: Future[SourceQueueWithComplete[StreamEvent]],
    onDrop: StreamEvent => Future[Boolean] = _ => Future.successful(true))
      extends InboundStreamSetup
  {
    override def connectToXmppStream
      (xmppStream: XmppStream)
      (implicit ec: ExecutionContext)
    : Future[Done] =
      for {
        sourceQueue <- sourceQueueFuture
        _ = loop(xmppStream, sourceQueue)
        Done <- sourceQueue.watchCompletion()
      }
        yield Done

    def loop
      (xmppStream: XmppStream,
       sourceQueue: SourceQueueWithComplete[StreamEvent])
      (implicit ec: ExecutionContext)
    : Unit =
      for {
        streamEventTry <- xmppStream.receiveStreamEvent()
          .map(se => Success(se))
          .recover[Try[StreamEvent]]{ case reason => Failure(reason) }

        shouldProceed <- streamEventTry match {
          case Failure(reason) =>
            sourceQueue.fail(reason)
            Future.successful(false)

          case Success(streamEvent) =>
            for {
              offerResult <- sourceQueue
                .offer(streamEvent)
                .recover {
                  case e /*: Exception */ =>
                    QueueOfferResult.Failure(e)
                }
              shouldProceedInner <-
                offerResult match {
                  case QueueOfferResult.Failure(reason) =>
                    sourceQueue.fail(reason) // TODO: rewrap the reason here?
                    Future.successful(false)

                  case QueueOfferResult.QueueClosed =>
                    sourceQueue.complete()
                    Future.successful(false)

                  case QueueOfferResult.Dropped =>
                    onDrop(streamEvent) // TODO: what if the chosen OverflowStrategy is other than DropNew?

                  case QueueOfferResult.Enqueued =>
                    Future.successful(true)
                }
            }
              yield shouldProceedInner
        }
      }
        if (shouldProceed) loop(xmppStream, sourceQueue)

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

  final case class OutboundStreamSetupSinkQueueFuture(sinkQueueFuture: Future[SinkQueue[StreamEvent]], sendTimeout: Timeout)
    extends OutboundStreamSetup
  {

    private def loop
      (donePromise: Promise[Done],
       sinkQueue: SinkQueue[StreamEvent],
       xmppStream: XmppStream)
      (implicit ec: ExecutionContext)
    : Unit =
      sinkQueue.pull()
        .flatMap {
          case None =>
            Future.successful(None)

          case Some(streamEvent) =>
            xmppStream.sendStreamEvent(streamEvent)(sendTimeout)
            Future.successful(Some(streamEvent))
        }
        .onComplete {
          case Success(None) =>
            donePromise.success(Done)

          case Success(Some(_)) =>
            loop(donePromise, sinkQueue, xmppStream)

          case Failure(reason) =>
            donePromise.failure(reason)
        }

    override def connectToXmppStream
      (xmppStream: XmppStream)
      (implicit ec: ExecutionContext)
    : Future[Done] = {
      val donePromise = Promise[Done]()
      for {
        sinkQueue <- sinkQueueFuture
        _ = loop(donePromise, sinkQueue, xmppStream)
        Done <- donePromise.future
      }
        yield Done
    }
  }

  def apply(): StanzasToStreamProtocol =
    StanzasToStreamProtocol(xmppProtocolInternals = XmppProtocol.Internals.empty)

}

final case class StanzasToStreamProtocol private(xmppProtocolInternals: XmppProtocol.Internals)
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
      .recover { case e /*: Exception */ => context.fail(e) }
  }
}
