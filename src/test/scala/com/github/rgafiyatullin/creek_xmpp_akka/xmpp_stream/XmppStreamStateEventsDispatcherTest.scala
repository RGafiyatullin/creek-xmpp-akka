package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream

import com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.stream.XmppStreamState
import org.scalatest.FlatSpec
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.Promise

object XmppStreamStateEventsDispatcherTest {
  sealed trait ADT
  object ADT {
    case object A extends ADT
    case object D extends ADT
    case object T extends ADT
  }
}

class XmppStreamStateEventsDispatcherTest extends FlatSpec with ScalaFutures {
  "dispatcher" should "work" in {
    implicit val ec = scala.concurrent.ExecutionContext.global
    import XmppStreamStateEventsDispatcherTest.ADT
    import XmppStreamState.EventsDispatcher

    val disp0 = EventsDispatcher.create[ADT]
    val (None, disp1) = disp0.addEvent(ADT.A)
    val (None, disp2) = disp1.addEvent(ADT.D)
    val (None, disp3) = disp2.addEvent(ADT.T)
    val promise1 = Promise[ADT]()
    val (Some(resolve1), disp4) = disp3.addConsumer(promise1)
    val promise2 = Promise[ADT]()
    val (Some(resolve2), disp5) = disp4.addConsumer(promise2)
    val promise3 = Promise[ADT]()
    val (Some(resolve3), disp6) = disp5.addConsumer(promise3)
    val promise4 = Promise[ADT]()
    val (None, disp7) = disp6.addConsumer(promise4)
    val promise5 = Promise[ADT]()
    val (None, disp8) = disp7.addConsumer(promise5)
    val promise6 = Promise[ADT]()
    val (None, disp9) = disp8.addConsumer(promise6)
    val (Some(resolve4), disp10) = disp9.addEvent(ADT.A)
    val (Some(resolve5), disp11) = disp10.addEvent(ADT.D)
    val (Some(resolve6), disp12) = disp11.addEvent(ADT.T)

    val everythingResolved =
      for {
        ADT.A <- promise1.future
        ADT.D <- promise2.future
        ADT.T <- promise3.future
        ADT.A <- promise4.future
        ADT.D <- promise5.future
        ADT.T <- promise6.future
      }
        yield ()

    resolve1()
    resolve2()
    resolve3()
    resolve4()
    resolve5()
    resolve6()

    whenReady(everythingResolved)(identity)

  }
}
