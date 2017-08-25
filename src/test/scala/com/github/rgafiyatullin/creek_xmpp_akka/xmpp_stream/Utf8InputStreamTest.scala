package com.github.rgafiyatullin.creek_xmpp_akka.xmpp_stream

import java.nio.charset.StandardCharsets

import com.github.rgafiyatullin.creek_xml.utf8.Utf8InputStream
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.immutable.Queue

class Utf8InputStreamTest extends FlatSpec with Matchers {
  "An empty Utf8InputStream" should "return (None,this)" in {
    val s0 = Utf8InputStream.empty
    s0.out should be (None, s0)
  }

  "Utf8InputStream" should "cope with one-byte char" in {
    val s0 = Utf8InputStream.empty
    val Right(s1) = s0.in('A'.toByte)
    val (a, s2) = s1.out
    a should be (Some('A'))
    s2.out should be (None, s2)
  }

  def commonStrTest(s: String): Unit = {
    val bytes = s.getBytes(StandardCharsets.UTF_8)
    val (chars, isOut) = bytes.foldLeft(Queue.empty[Char], Utf8InputStream.empty) {
      case ((acc, is), b) =>
        is.out match {
          case (None, _) =>
            val Right(isNext) = is.in(b)
            (acc, isNext)
          case (Some(ch), is1) =>
            val Right(isNext) = is1.in(b)
            (acc.enqueue(ch), isNext)
        }
    }
    val charsFinal = isOut.out._1.map(chars.enqueue).getOrElse(chars)

    charsFinal.mkString should be (s)
    ()
  }

  it should "cope with single ascii char" in {
    commonStrTest("a")
  }

  it should "cope with several ascii chars" in {
    commonStrTest("ab")
  }

  it should "cope with a number of ascii chars" in {
    commonStrTest("abcdefghijklmnop")
  }

  it should "cope with single ru-char" in {
    commonStrTest("я")
  }
}
