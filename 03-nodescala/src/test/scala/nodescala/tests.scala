package nodescala

import org.scalatest.concurrent.AsyncAssertions

import scala.language.postfixOps
import scala.util.{Try, Success, Failure}
import scala.collection._
import scala.concurrent._
import ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.async.Async.{async, await}
import NodeScala._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest._
import org.scalatest.Matchers._



@RunWith(classOf[JUnitRunner])
class NodeScalaSuite extends FunSuite with AsyncAssertions {

  test("A Future should always be completed") {
    val always = Future.always(517)

    assert(Await.result(always, 0 nanos) == 517)
  }
  test("A Future should never be completed") {
    val never = Future.never[Int]

    try {
      Await.result(never, 1 second)
      assert(false)
    } catch {
      case t: TimeoutException => // ok!
    }
  }
  test("All futures should be completed") {

    val all = Future.all(List(Future { 1 }, Future { 2 }, Future { 3 }))

    assert(Await.result(all, 1 second) == List(1, 2, 3))
  }

  test("All futures should return exception") {

    val all = Future.all(List(Future { 1 }, Future { 2 }, Future { throw new Exception }))

    try {
      Await.result(all, 1 second)
      assert(false)
    } catch {
      case e: Exception => // ok
    }
  }


  test("With async") {
    val p = Promise[Int]()
    val all = Future.all(List(Future { 1 }, p.future, Future { 3 }))


    try {
      Await.ready(p.future, 1 seconds)
    } catch {
      case t: TimeoutException => // ok!
    }

    p.success(2)

    assert(Await.result(all, 500 millis) == List(1, 2, 3))
  }

  test("Any") {
    val any = Future.any(List(Future { 1 }, Future { 2 }, Future { 3 }))

    val result = Await.result(any, 1 second)
    result should (be >= 1 and be <= 3)
  }

  test("Now immediate") {
    val now = Future { 1 }

    assert(now.now == 1)
  }

  test("Now no value") {
    val p = Promise[Int]()

    try {
      val now = p.future.now
      fail()
    } catch {
      case e: NoSuchElementException => // ok!
    }
  }

  test("ContinueWith") {
    val continuedWith:Future[String] = Future { 1 }.continueWith(f => "ok")

    assert(Await.result(continuedWith, 1 second) == "ok")
  }

  test("continue") {
    val continue:Future[String] = Future { 1 }.continue(f => "ok" + f.get)

    assert(Await.result(continue, 1 second) == "ok1")
  }

  test("A Future should complete after 3s when using a delay of 1s") {
    val p = Promise[Unit]()

    Future {
      blocking {
        Future.delay(1 second) onSuccess {
          case _ => p.complete(Try(()))
        }
      }
    }

    Await.ready(p.future, 3 second)
  }

  test("A Future should be completed after 1s delay") {
    val w = new Waiter
    val start = System.currentTimeMillis()

    Future.delay(1 second) onComplete { case _ =>
      val duration = System.currentTimeMillis() - start
      duration should (be >= 1000L and be < 1100L)

      w.dismiss()
    }

    w.await(timeout(2 seconds))
  }

  test("Two sequential delays of 1s should delay by 2s") {
    val w = new Waiter
    val start = System.currentTimeMillis()

    val combined = for {
      f1 <- Future.delay(1 second)
      f2 <- Future.delay(1 second)
    } yield ()

    combined onComplete { case _ =>
      val duration = System.currentTimeMillis() - start
      duration should (be >= 2000L and be < 2100L)

      w.dismiss()
    }

    w.await(timeout(3 seconds))
  }

  test("A Future should not complete after 1s when using a delay of 3s") {
    val p = Promise[Unit]()

    Future {
      blocking {
        Future.delay(3 second) onSuccess {
          case _ => p.complete(Try(()))
        }
      }
    }

    try {
      Await.result(p.future, 1 second)
      assert(false)
    } catch {
      case t: TimeoutException => // ok!
    }
  }
  test("Server should be stoppable if receives infinite  response") {
    val dummy = new DummyServer(8191)
    val dummySubscription = dummy.start("/testDir") {
      request => Iterator.continually("a")
    }

    // wait until server is really installed
    Thread.sleep(500)

    val webpage = dummy.emit("/testDir", Map("Any" -> List("thing")))
    try {
      // let's wait some time
      Await.result(webpage.loaded.future, 1 second)
      fail("infinite response ended")
    } catch {
      case e: TimeoutException =>
    }

    // stop everything
    dummySubscription.unsubscribe()
    Thread.sleep(500)
    webpage.loaded.future.now // should not get NoSuchElementException
  }

//  test("run") {
//    val working = Future.run() { ct =>
//      Future {
//        while (ct.nonCancelled) {
//          println("working")
//        }
//        println("done")
//      }
//    }
//    Future.delay(1 seconds) onSuccess {
//      case _ => working.unsubscribe()
//    }
//  }

  class DummyExchange(val request: Request) extends Exchange {
    @volatile var response = ""
    val loaded = Promise[String]()
    def write(s: String) {
      response += s
    }
    def close() {
      loaded.success(response)
    }
  }

  class DummyListener(val port: Int, val relativePath: String) extends NodeScala.Listener {
    self =>

    @volatile private var started = false
    var handler: Exchange => Unit = null

    def createContext(h: Exchange => Unit) = this.synchronized {
      assert(started, "is server started?")
      handler = h
    }

    def removeContext() = this.synchronized {
      assert(started, "is server started?")
      handler = null
    }

    def start() = self.synchronized {
      started = true
      new Subscription {
        def unsubscribe() = self.synchronized {
          started = false
        }
      }
    }

    def emit(req: Request) = {
      val exchange = new DummyExchange(req)
      if (handler != null) handler(exchange)
      exchange
    }
  }

  class DummyServer(val port: Int) extends NodeScala {
    self =>
    val listeners = mutable.Map[String, DummyListener]()

    def createListener(relativePath: String) = {
      val l = new DummyListener(port, relativePath)
      listeners(relativePath) = l
      l
    }

    def emit(relativePath: String, req: Request) = this.synchronized {
      val l = listeners(relativePath)
      l.emit(req)
    }
  }
  test("Server should serve requests") {
    val dummy = new DummyServer(8191)
    val dummySubscription = dummy.start("/testDir") {
      request => for (kv <- request.iterator) yield (kv + "\n").toString
    }

    // wait until server is really installed
    Thread.sleep(500)

    def test(req: Request) {
      val webpage = dummy.emit("/testDir", req)
      val content = Await.result(webpage.loaded.future, 1 second)
      val expected = (for (kv <- req.iterator) yield (kv + "\n").toString).mkString
      assert(content == expected, s"'$content' vs. '$expected'")
    }

    test(immutable.Map("StrangeRequest" -> List("Does it work?")))
    test(immutable.Map("StrangeRequest" -> List("It works!")))
    test(immutable.Map("WorksForThree" -> List("Always works. Trust me.")))

    dummySubscription.unsubscribe()
  }

}




