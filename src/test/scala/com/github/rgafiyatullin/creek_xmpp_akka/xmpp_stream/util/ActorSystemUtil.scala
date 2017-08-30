package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream.util

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.event.Logging.LogLevel
import com.github.rgafiyatullin.owl_akka_goodies.actor_future.ActorStdReceive
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

trait ActorSystemUtil extends ScalaFutures {
  implicit val defaultPatience: PatienceConfig

  def randomString(): String = {
    new Random().nextInt().abs.toString
  }
  def randomPort(): Int = {
    new Random().nextInt().abs % (32 * 1024) + 32 * 1024
  }

  def futureShouldSucceed[T](f: => Future[T]): Unit = {
    whenReady(f)(identity)
    ()
  }

  def withActorSystem[T](name: String = randomString())(f: ActorSystem => Future[T]): Future[T] = {
    val actorSystem = ActorSystem(name)
    actorSystem.eventStream.setLogLevel(LogLevel(4))

    implicit val ec: ExecutionContext = actorSystem.dispatcher
    for {
      result <- f(actorSystem)
      _ <- actorSystem.terminate()
    }
      yield result
  }

  def createAnActor(actorSystem: ActorSystem): ActorRef =
    actorSystem.actorOf(Props(new Actor with ActorStdReceive {
      override def receive: Receive =
        stdReceive.discard
    }))

}