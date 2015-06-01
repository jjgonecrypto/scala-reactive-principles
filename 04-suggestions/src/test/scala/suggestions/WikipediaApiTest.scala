package suggestions


import rx.lang.scala.subjects.PublishSubject

import language.postfixOps
import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Try, Success, Failure}
import rx.lang.scala._
import org.scalatest._
import gui._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner


@RunWith(classOf[JUnitRunner])
class WikipediaApiTest extends FunSuite {

  object mockApi extends WikipediaApi {
    def wikipediaSuggestion(term: String) = Future {
      if (term.head.isLetter) {
        for (suffix <- List(" (Computer Scientist)", " (Footballer)")) yield term + suffix
      } else {
        List(term)
      }
    }
    def wikipediaPage(term: String) = Future {
      "Title: " + term
    }
  }

  import mockApi._

  test("WikipediaApi should make the stream valid using sanitized") {
    val notvalid = Observable.just("erik", "erik meijer is in the house", "martin")
    val valid = notvalid.sanitized

    var count = 0
    var completed = false

    val sub = valid.subscribe(
      term => {
        assert(term.forall(_ != ' '))
        count += 1
      },
      t => assert(false, s"stream error $t"),
      () => completed = true
    )
    assert(completed && count == 3, "completed: " + completed + ", event count: " + count)
  }

  test("WikipediaApi should handle recover") {
    val channel = Subject[Int]()

    val error = new Exception("error")

    val recovered = channel.recovered

    val results = mutable.Buffer[Try[Int]]()

    recovered.subscribe { results += _ }

    channel.onNext(1)
    channel.onNext(2)
    channel.onNext(3)
    channel.onError(error)

    assert(results == Seq(Success(1), Success(2), Success(3), Failure(error)))
  }

  test("WikipediaApi should correctly use timedOut") {
    val channel = Subject[Int]()

    val timedOut = channel.timedOut(1L)

    val results = mutable.Buffer[Int]()

    timedOut.subscribe { results += _ }

    for (i <- 1 until 6) {
      Thread.sleep(300)
      channel.onNext(i)
    }

    assert(results == Seq(1, 2, 3))
  }

  test("WikipediaApi should correctly use timedOut with errors") {
    val channel = Subject[Int]()

    val timedOut = channel.timedOut(1L)

    val results = mutable.Buffer[Try[Int]]()

    timedOut.subscribe(s => (), t => results += Failure(t))

    val error = new Exception("error")
    channel.onError(error)

    assert(results == Seq(Failure(error)))
  }

  test("WikipediaApi should correctly use timedOut with auto-complete") {
    val channel = Subject[Int]()

    val timedOut = channel.timedOut(1L)

    val results = mutable.Buffer[Int]()

    timedOut.subscribe { results += _ }

    Thread.sleep(1100)

    assert(results == Seq())
  }


  test("WikipediaApi should correctly use concatRecovered and recover") {
    val numbers = Observable.just(1, 2, 3, 4, 5)

    val error = new Exception

    val streamed = numbers concatRecovered(
      (num) => if (num != 4) Observable.just(num) else Observable.error(error))

    val results = mutable.Buffer[Try[Int]]()

    streamed.subscribe { results += _ }

    assert(results == Seq(Success(1), Success(2), Success(3), Failure(error), Success(5)))
  }
  
  test("WikipediaApi should correctly use concatRecovered in order") {

    val streamed = Observable.just(1, 2, 3).concatRecovered(num => Observable.just(num, num, num))

    val results = mutable.Buffer[Try[Int]]()

    streamed.subscribe { results += _ }

    assert(results == Seq(Success(1), Success(1), Success(1), Success(2), Success(2), Success(2), Success(3), Success(3), Success(3)))
    
  }

  test("WikipediaApi should correctly use concatRecovered") {
    val requests = Observable.just(1, 2, 3)
    val remoteComputation = (n: Int) => Observable.just(0 to n : _*)
    val responses = requests concatRecovered remoteComputation
    val sum = responses.foldLeft(0) { (acc, tn) =>
      tn match {
        case Success(n) => acc + n
        case Failure(t) => throw t
      }
    }
    var total = -1
    val sub = sum.subscribe {
      s => total = s
    }
    assert(total == (1 + 1 + 2 + 1 + 2 + 3), s"Sum: $total")
  }

}
