package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream

import akka.actor.ActorRef
import com.github.rgafiyatullin.creek_xmpp.streams.{InputStream, OutputStream, StreamEvent}
import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.transports.XmppTransport

import scala.collection.immutable.Queue
import scala.concurrent.{ExecutionContext, Future, Promise}

sealed trait XmppStreamState {

  val eventsDispatcher: XmppStreamState.EventsDispatcher[StreamEvent]

  protected def usingField[Self, Field, Result]
    (fieldIn: Field, updateField: Field => Self)
    (f: Field => (Result, Field)): (Result, Self) =
  {
    val (result, fieldNext) = f(fieldIn)
    (result, updateField(fieldNext))
  }

  protected def usingFieldAsync[Self, Field, Result]
    (fieldIn: Field, updateField: Field => Self)
    (f: Field => Future[(Result, Field)])
    (implicit ec: ExecutionContext)
  : Future[(Result, Self)] =
    for {
      (result, fieldNext) <- f(fieldIn)
    }
      yield (result, updateField(fieldNext))

}

object XmppStreamState {

  sealed trait EventsDispatcher[A] {
    def addEvent(event: A): (Option[() => Unit], EventsDispatcher[A])
    def addConsumer(consumer: Promise[A]): (Option[() => Unit], EventsDispatcher[A])

    def failAllConsumers(reason: Throwable): (Option[() => Unit], EventsDispatcher[A])
  }

  object EventsDispatcher {
    def create[Event]: EventsDispatcher[Event] = Equilibrium[Event]()

    private final case class EventsDeficit[A](
      consumers: Queue[Promise[A]])
        extends EventsDispatcher[A]
    {
      assert(consumers.nonEmpty)

      override def addEvent(event: A): (Option[() => Unit], EventsDispatcher[A]) = {
        val head = consumers.head
        val tail = consumers.tail
        val resolveFunction = () => { head.success(event); ()}
        val nextDispatcher =
          if (tail.isEmpty) Equilibrium[A]()
          else copy(consumers = tail)

        (Some(resolveFunction), nextDispatcher)
      }

      override def addConsumer(consumer: Promise[A]): (Option[() => Unit], EventsDispatcher[A]) =
        (None, copy(consumers = consumers.enqueue(consumer)))

      def failAllConsumers(reason: Throwable): (Option[() => Unit], EventsDispatcher[A]) =
        (Some(() => consumers.foreach(_.failure(reason))), this)
    }


    private final case class Equilibrium[A]()
      extends EventsDispatcher[A]
    {
      override def addEvent(event: A): (Option[() => Unit], EventsDispatcher[A]) =
        (None, EventsExcess(Queue(event)))

      override def addConsumer(consumer: Promise[A]): (Option[() => Unit], EventsDispatcher[A]) =
        (None, EventsDeficit(Queue(consumer)))

      def failAllConsumers(reason: Throwable): (Option[() => Unit], EventsDispatcher[A]) =
        (None, this)
    }


    private final case class EventsExcess[A](
      events: Queue[A])
        extends EventsDispatcher[A]
    {
      assert(events.nonEmpty)

      override def addEvent(event: A): (Option[() => Unit], EventsDispatcher[A]) =
        (None, copy(events = events.enqueue(event)))

      override def addConsumer(consumer: Promise[A]): (Option[() => Unit], EventsDispatcher[A]) = {
        val head = events.head
        val tail = events.tail
        val resolveFunction = () => { consumer.success(head); () }
        val nextDispatcher =
          if (tail.isEmpty) Equilibrium[A]()
          else copy(events = tail)

        (Some(resolveFunction), nextDispatcher)
      }

      def failAllConsumers(reason: Throwable): (Option[() => Unit], EventsDispatcher[A]) = (None, this)
    }
  }





  final case class Connected(
    connection: ActorRef,
    transport: XmppTransport,
    eventsDispatcher: EventsDispatcher[StreamEvent],
    outputStream: OutputStream = OutputStream.empty,
    inputStream: InputStream = InputStream.empty,
    inputStreamFailureOption: Option[Exception] = None)
      extends XmppStreamState
  {
    def withInputStreamFailure(e: Exception): Connected =
      copy(inputStreamFailureOption = Some(e))



    def usingTransport[T](f: XmppTransport => (T, XmppTransport)): (T, Connected) =
      usingField[Connected, XmppTransport, T](transport, t => copy(transport = t))(f)

    def usingTransportAsync[T](
      f: XmppTransport => Future[(T, XmppTransport)])(
      implicit ec: ExecutionContext)
    : Future[(T, Connected)] =
      usingFieldAsync[Connected, XmppTransport, T](transport, t => copy(transport = t))(f)

    def usingInputStream[T](f: InputStream => (T, InputStream)) =
      usingField[Connected, InputStream, T](inputStream, is => copy(inputStream = is))(f)

    def usingInputStreamAsync[T]
      (f: InputStream => Future[(T, InputStream)])
      (implicit ec: ExecutionContext)
    : Future[(T, Connected)] =
      usingFieldAsync[Connected, InputStream, T](inputStream, is => copy(inputStream = is))(f)


    def usingOutputStream[T](f: OutputStream => (T, OutputStream)) =
      usingField[Connected, OutputStream, T](outputStream, os => copy(outputStream = os))(f)

    def usingOutputStreamAsync[T]
      (f: OutputStream => Future[(T, OutputStream)])
      (implicit ec: ExecutionContext)
    : Future[(T, Connected)] =
      usingFieldAsync[Connected, OutputStream, T](outputStream, os => copy(outputStream = os))(f)


    def usingEventsDispatcher[T]
      (f: EventsDispatcher[StreamEvent] => (T, EventsDispatcher[StreamEvent]))
    : (T, Connected) =
      usingField[Connected, EventsDispatcher[StreamEvent], T](eventsDispatcher, ed => copy(eventsDispatcher = ed))(f)

    def usingEventsDispatcherAsync[T]
      (f: EventsDispatcher[StreamEvent] => Future[(T, EventsDispatcher[StreamEvent])])
      (implicit ec: ExecutionContext)
    : Future[(T, Connected)] =
      usingFieldAsync[Connected, EventsDispatcher[StreamEvent], T](eventsDispatcher, ed => copy(eventsDispatcher = ed))(f)

  }

  final case class Connecting(
    eventsDispatcher: EventsDispatcher[StreamEvent])
      extends XmppStreamState
  {
    def toFailed: Failed =
      Failed(eventsDispatcher)

    def toConnected(
      connection: ActorRef,
      transport: XmppTransport)
    : Connected =
      Connected(connection, transport, eventsDispatcher)
  }

  final case class Failed(eventsDispatcher: EventsDispatcher[StreamEvent])
    extends XmppStreamState

  final case class Empty(eventsDispatcher: EventsDispatcher[StreamEvent])
    extends XmppStreamState
}
