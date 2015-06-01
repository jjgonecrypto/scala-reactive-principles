package calculator

import org.scalatest.FunSuite

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.scalatest._

import TweetLength.MaxTweetLength

@RunWith(classOf[JUnitRunner])
class CalculatorSuite extends FunSuite with ShouldMatchers {

  /** ****************
    * * TWEET LENGTH **
    * *****************/

  def tweetLength(text: String): Int =
    text.codePointCount(0, text.length)

  test("tweetRemainingCharsCount with a constant signal") {
    val result = TweetLength.tweetRemainingCharsCount(Var("hello world"))
    assert(result() == MaxTweetLength - tweetLength("hello world"))

    val tooLong = "foo" * 200
    val result2 = TweetLength.tweetRemainingCharsCount(Var(tooLong))
    assert(result2() == MaxTweetLength - tweetLength(tooLong))
  }

  test("tweetRemainingCharsCount with a supplementary char") {
    val result = TweetLength.tweetRemainingCharsCount(Var("foo blabla \uD83D\uDCA9 bar"))
    assert(result() == MaxTweetLength - tweetLength("foo blabla \uD83D\uDCA9 bar"))
  }

  test("tweetRemainingCharsCount with a changing signal") {
    val amount = Var("hello world")
    val result = TweetLength.tweetRemainingCharsCount(amount)
    assert(result() == MaxTweetLength - tweetLength("hello world"))

    amount() = "hello"
    assert(result() == MaxTweetLength - tweetLength("hello"))
  }

  test("colorForRemainingCharsCount with a constant signal") {
    val resultGreen1 = TweetLength.colorForRemainingCharsCount(Var(52))
    assert(resultGreen1() == "green")
    val resultGreen2 = TweetLength.colorForRemainingCharsCount(Var(15))
    assert(resultGreen2() == "green")

    val resultOrange1 = TweetLength.colorForRemainingCharsCount(Var(12))
    assert(resultOrange1() == "orange")
    val resultOrange2 = TweetLength.colorForRemainingCharsCount(Var(0))
    assert(resultOrange2() == "orange")

    val resultRed1 = TweetLength.colorForRemainingCharsCount(Var(-1))
    assert(resultRed1() == "red")
    val resultRed2 = TweetLength.colorForRemainingCharsCount(Var(-5))
    assert(resultRed2() == "red")
  }

  test("colorForRemainingCharsCount with a changing signal") {
    val signal = Var(52)
    val result = TweetLength.colorForRemainingCharsCount(signal)
    assert(result() == "green")

    signal() = -1
    assert(result() == "red")

    signal() = 0
    assert(result() == "orange")

    signal() = 1
    assert(result() == "orange")

    signal() = 200
    assert(result() == "green")

    signal() = 14
    assert(result() == "orange")
  }

  test("computeDelta with changing values") {
    val a = Var(1.0)
    val b = Var(2.0)
    val c = Var(1.0)

    val result = Polynomial.computeDelta(a, b, c)

    assert(result() == 0)

    a() = 2.0
    assert(result() == -4)

    c() = 0.0
    assert(result() == 4)

    b() = 4.0
    assert(result() == 16)
  }

  test("computeSolutions with changing values") {
    val a = Var(1.0)
    val b = Var(2.0)
    val c = Var(1.0)
    val delta = Polynomial.computeDelta(a, b, c)

    val result = Polynomial.computeSolutions(a, b, c, delta)

    assert(result() == Set(-1.0))

    a() = 2.0
    assert(result() == Set())

    b() = 3.0
    assert(result() == Set(-0.5, -1))

    c() = 0.0
    assert(result() == Set(0, -1.5))
  }

  test("eval with changing vals") {
    val a:Var[Expr] = Var(Literal(10.0)) // 10
    val b:Var[Expr] = Var(Plus(Ref("a"), Literal(2.0))) //12
    val c:Var[Expr] = Var(Times(Ref("a"), Literal(0.4))) //4
    val d:Var[Expr] = Var(Divide(Ref("b"), Ref("c"))) // 3
    val refs = Map(("a" -> a), ("b" -> b), ("c" -> c), ("d" -> d))

    assert(Calculator.eval(Literal(2.0), refs) == 2.0)
    assert(Calculator.eval(a(), refs) == 10)
    assert(Calculator.eval(b(), refs) == 12)
    assert(Calculator.eval(c(), refs) == 4)
    assert(Calculator.eval(d(), refs) == 3)

    a() = Literal(20)
    assert(Calculator.eval(a(), refs) == 20)
    assert(Calculator.eval(b(), refs) == 22)
    assert(Calculator.eval(c(), refs) == 8)
    assert(Calculator.eval(d(), refs) == (22.0/8.0))
  }

  test("eval with no match") {
    assert(Calculator.eval(Ref("a"), Map()).isNaN)
  }

  test("computeVals with changing vals") {
    val a: Var[Expr] = Var(Literal(10.0)) // 10
    val b: Var[Expr] = Var(Plus(Ref("a"), Literal(2.0))) //12
    val c: Var[Expr] = Var(Times(Ref("a"), Literal(0.4))) //4
    val d: Var[Expr] = Var(Divide(Ref("b"), Ref("c"))) // 3
    val refs = Map(("a" -> a), ("b" -> b), ("c" -> c), ("d" -> d))

    val out = Calculator.computeValues(refs)

    assert(out("a")() == 10.0)
    assert(out("b")() == 12.0)
    assert(out("c")() == 4.0)
    assert(out("d")() == 3.0)

    a() = Literal(20)
    assert(out("a")() == 20.0)
    assert(out("b")() == 22.0)
    assert(out("c")() == 8.0)
    assert(out("d")() == (22.0/8.0))
  }

  test("computeVals with own cyclic deps") {
    val a: Var[Expr] = Var(Plus(Ref("a"), Literal(10.0)))

    val refs = Map(("a" -> a))

    val out = Calculator.computeValues(refs)

    assert(out("a")().isNaN)

  }

  test("computeVals with cyclic deps") {
    val a: Var[Expr] = Var(Plus(Ref("b"), Literal(10.0)))
    val b: Var[Expr] = Var(Plus(Ref("a"), Literal(2.0)))

    val refs = Map(("a" -> a), ("b" -> b))

    val out = Calculator.computeValues(refs)

    assert(out("a")().isNaN)
    assert(out("b")().isNaN)

  }

  test("computeVals with deep cyclic deps") {
    val a: Var[Expr] = Var(Plus(Ref("b"), Literal(10.0)))
    val b: Var[Expr] = Var(Plus(Ref("c"), Literal(2.0)))
    val c: Var[Expr] = Var(Plus(Ref("a"), Literal(2.0)))

    val refs = Map(("a" -> a), ("b" -> b), ("c" -> c))

    val out = Calculator.computeValues(refs)

    assert(out("a")().isNaN)
    assert(out("b")().isNaN)
    assert(out("c")().isNaN)

  }

  test("computeVals with something equal to NaN") {
    val a: Var[Expr] = Var(Plus(Ref("a"), Literal(10.0)))
    val b: Var[Expr] = Var(Ref("a"))

    val refs = Map(("a" -> a), ("b" -> b))

    val out = Calculator.computeValues(refs)

    assert(out("a")().isNaN)
    assert(out("b")().isNaN)

  }

}