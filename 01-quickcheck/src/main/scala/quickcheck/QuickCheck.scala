package quickcheck

import common._

import org.scalacheck._
import Arbitrary._
import Gen._
import Prop._

abstract class QuickCheckHeap extends Properties("Heap") with IntHeap {


  property("minOnEmpty") = forAll { a: Int =>
    val h = insert(a, empty)
    findMin(h) == a
  }

  property("deleteAfterInsert") = forAll { a: Int =>
     val h = insert(a, empty)
     isEmpty(deleteMin(h))
  }

  property("doubleInsert") = forAll { (a: Int, b: Int) =>
    findMin(insert(b, insert(a, empty))) == (if (a < b) a else b)
  }

  property("melding") = forAll { (a: Int, b: Int) =>
    findMin(meld(insert(b, empty), insert(a, empty))) == (if (a < b) a else b)
  }

  property("meldingThenDelete") = forAll { (a: Int, b: Int) =>
    findMin(deleteMin(meld(insert(b, empty), insert(a, empty)))) == (if (b > a) b else a)
  }

  property("doubleInsertWithDelete") = forAll { (a: Int, b: Int) =>
    findMin(deleteMin(insert(b, insert(a, empty)))) == (if (b > a) b else a)
  }

  property("gen1") = forAll { (h: H) =>
    val m = if (isEmpty(h)) 0 else findMin(h)
    findMin(insert(m, h))==m
  }

  property("minOfMelding") = forAll { (h1: H, h2: H) =>
    findMin(meld(h1, h2)) == (if (findMin(h1) <= findMin(h2)) findMin(h1) else findMin(h2))
  }

  def isSorted(h: H) = {
    def recurse(last: Int, heap: H): Boolean = {
      if (isEmpty(heap)) true
      else last <= findMin(heap) && recurse(findMin(heap), deleteMin(heap))
    }
    if (isEmpty(h)) true
    else recurse(findMin(h), deleteMin(h))
  }

  def creator(num: Int, h: H) = {
    insert(num, h)
  }

  def length(h: H): Int = {
    if (isEmpty(h)) 0
    else 1 + length(deleteMin(h))
  }

  property("reduceSize") = forAll { (h: H) =>
    length(deleteMin(h)) == length(h) - 1
  }

  property("combinedSize") = forAll { (h1: H, h2: H) =>
    length(meld(h1, h2)) == length(h1) + length(h2)
  }

  property("sorted") = forAll { (h: H) =>
    isSorted(h)
  }

  property("sortedMeld") = forAll { (h1: H, h2: H) =>
    isSorted(deleteMin(meld(h1, h2)))
  }

  property("contents") = forAll{ (i1: Int, i2: Int, i3: Int, i4: Int, i5: Int) =>
    val list = List(i1, i2, i3, i4, i5)
    def populate(list: List[Int], h: H): H = list match {
      case Nil => h
      case head::tail => populate(tail, insert(head, h))
    }

    def traverse(h: H, list: List[Int]): Boolean = list match {
      case Nil => true
      case head::tail => findMin(h) == head && traverse(deleteMin(h), tail)
    }
    traverse(populate(list, empty), list.sorted)
  }

  lazy val genHeap: Gen[H] = for {
    a <- arbitrary[Int]
    h <- oneOf(const(empty), genHeap)
  } yield insert(a, h)

  implicit lazy val arbHeap: Arbitrary[H] = Arbitrary(genHeap)

}
