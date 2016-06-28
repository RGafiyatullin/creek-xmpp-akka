package com.github.rgafiyatullin.creek_xmpp_akka.common.actor_api

sealed trait Result[+Positive, +Negative] {
  def isOk: Boolean
  def isErr: Boolean = ! isOk

  def ok: Positive = okOption.get
  def err: Negative = errOption.get

  def okOption: Option[Positive]
  def errOption: Option[Negative]

  def fold[T](ifErr: Negative => T)(ifOk: Positive => T): T =
    if (isOk)
      ifOk(ok)
    else
      ifErr(err)

  def okMap[NextPositive](map: Positive => NextPositive): Result[NextPositive, Negative]
  def errMap[NextNegative](map: Negative => NextNegative): Result[Positive, NextNegative]

  def map[P](f: Positive => P): Result[P, Negative] = flatMap { value => Ok(f(value)) }

  def flatMap[P, N >: Negative](f: Positive => Result[P, N]): Result[P, N] =
    this match {
      case Ok(value) => f(value)
      case Err(err) => Err(err)
    }

  def foreach(f: Positive => Unit): Unit =
    this match {
      case Ok(value) => f(value)
      case _ => ()
    }
}

object Result {
  def fromOption[Positive, Negative](errValue: Negative)(okValueOption: Option[Positive]): Result[Positive, Negative] =
    okValueOption
      .fold(Err(errValue): Result[Positive, Negative])(Ok(_))
}


final case class Ok[+Positive](value: Positive) extends Result[Positive, Nothing] {
  override def isOk: Boolean = true
  override def okOption: Option[Positive] = Some(value)
  override def errOption: Option[Nothing] = None

  override def okMap[NextPositive](
                  map: Positive => NextPositive
                ): Result[NextPositive, Nothing] = Ok(map(value))
  override def errMap[NextNegative](
                  map: Nothing => NextNegative
                ): Result[Positive, NextNegative] = this
}

//case object Ok extends Result[Unit, Nothing] {
//  override def isOk: Boolean = true
//  override def errOption: Option[Nothing] = None
//  override def okOption: Option[Unit] = Some(())
//
//  override def okMap[NextPositive](
//                  map: Unit => NextPositive
//                ): Result[NextPositive, Nothing] = Ok(map(()))
//
//  override def errMap[NextNegative](
//                  map: Nothing => NextNegative
//                ): Result[Unit, NextNegative] = this
//}

final case class Err[+Negative](value: Negative) extends Result[Nothing, Negative] {
  override def isOk: Boolean = false
  override def okOption: Option[Nothing] = None
  override def errOption: Option[Negative] = Some(value)

  override def okMap[NextPositive](
                  map: Nothing => NextPositive
                ): Result[NextPositive, Negative] = this

  override def errMap[NextNegative](
                  map: Negative => NextNegative
                ): Result[Nothing, NextNegative] = Err(map(value))
}

//case object Err extends Result[Nothing, Unit] {
//  override def isOk: Boolean = false
//  override def errOption: Option[Unit] = Some(())
//  override def okOption: Option[Nothing] = None
//
//  override def okMap[NextPositive](
//                  map: Nothing => NextPositive
//                ): Result[NextPositive, Unit] = this
//
//  override def errMap[NextNegative](
//                  map: Unit => NextNegative
//                ): Result[Nothing, NextNegative] = Err(map(()))
//}


