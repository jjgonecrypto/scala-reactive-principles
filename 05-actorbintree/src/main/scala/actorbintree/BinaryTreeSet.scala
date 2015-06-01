/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package actorbintree

import java.util.UUID

import akka.actor._
import scala.collection.immutable.Queue
import scala.util.Random

object BinaryTreeSet {

  trait Operation {
    def requester: ActorRef
    def id: Int
    def elem: Int
  }

  trait OperationReply {
    def id: Int
  }

  /** Request with identifier `id` to insert an element `elem` into the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Insert(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to check whether an element `elem` is present
    * in the tree. The actor at reference `requester` should be notified when
    * this operation is completed.
    */
  case class Contains(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request with identifier `id` to remove the element `elem` from the tree.
    * The actor at reference `requester` should be notified when this operation
    * is completed.
    */
  case class Remove(requester: ActorRef, id: Int, elem: Int) extends Operation

  /** Request to perform garbage collection*/
  case object GC

  /** Holds the answer to the Contains request with identifier `id`.
    * `result` is true if and only if the element is present in the tree.
    */
  case class ContainsResult(id: Int, result: Boolean) extends OperationReply
  
  /** Message to signal successful completion of an insert or remove operation. */
  case class OperationFinished(id: Int) extends OperationReply

}


class BinaryTreeSet extends Actor {
  import BinaryTreeSet._
  import BinaryTreeNode._

  def createRoot: ActorRef = context.actorOf(BinaryTreeNode.props(0, initiallyRemoved = true))

  var root = createRoot

  // optional
  var pendingQueue = Queue.empty[Operation]

  // optional
  def receive = normal

  var requestCache = Map[Int, ActorRef]()

  // optional
  /** Accepts `Operation` and `GC` messages. */
  val normal: Receive = {
    case Contains(requestor, id, elem) => {
      requestCache += (id -> requestor)
      root ! Contains(requestor, id, elem)
    }
    case ContainsResult(id, result) => requestCache(id) ! ContainsResult(id, result)
    case Insert(requestor, id, elem) => {
      requestCache += (id -> requestor)
      root ! Insert(requestor, id, elem)
    }
    case Remove(requestor, id, elem) => {
      requestCache += (id -> requestor)
      root ! Remove(requestor, id, elem)
    }
    case OperationFinished(id) => requestCache(id) ! OperationFinished(id)
    case GC => {
      val newRoot = createRoot

      // replace context stack with garbage collection mode
      context.become(garbageCollecting(newRoot))

      // start the copying
      root ! CopyTo(newRoot)

    }

  }

  // optional
  /** Handles messages while garbage collection is performed.
    * `newRoot` is the root of the new binary tree where we want to copy
    * all non-removed elements into.
    */
  def garbageCollecting(newRoot: ActorRef): Receive = {
    case CopyFinished => {

      // finally, remove the GC state for messages
      context.unbecome()

      context.stop(root)

      // switch the root to the new one
      root = newRoot

      // then process the queue...
      while (pendingQueue.size > 0) {
        root ! pendingQueue.head
        pendingQueue = pendingQueue.tail
      }

    }
    case op:Operation => {
      pendingQueue = pendingQueue.enqueue(op)
    }

  }

}

object BinaryTreeNode {
  trait Position

  case object Left extends Position
  case object Right extends Position

  case class CopyTo(treeNode: ActorRef)
  case object CopyFinished

  def props(elem: Int, initiallyRemoved: Boolean) = Props(classOf[BinaryTreeNode],  elem, initiallyRemoved)
}

class BinaryTreeNode(val elem: Int, initiallyRemoved: Boolean) extends Actor {
  import BinaryTreeNode._
  import BinaryTreeSet._

  var subtrees = Map[Position, ActorRef]()
  var removed = initiallyRemoved

  // optional
  def receive = normal

  val rnd = new Random()
  val RND_MAX = 999999

  // optional
  /** Handles `Operation` messages and `CopyTo` requests. */
  val normal: Receive = {
    case Contains(requestor, id, e) => {
      if (elem == e) requestor ! ContainsResult(id, !removed)
      else if (elem > e && subtrees.contains(Left))
        subtrees(Left) ! Contains(requestor, id, e)
      else if (elem < e && subtrees.contains(Right))
        subtrees(Right) ! Contains(requestor, id, e)
      else requestor ! ContainsResult(id, false)
    }
    case Insert(requestor, id, e) => {
      if (elem > e) {
        if (subtrees.contains(Left)) subtrees(Left) ! Insert(requestor, id, e)
        else {
          subtrees += (Left -> context.actorOf(BinaryTreeNode.props(e, initiallyRemoved = false)))
          requestor ! OperationFinished(id)
        }
      } else if (elem < e) {
        if (subtrees.contains(Right)) subtrees(Right) ! Insert(requestor, id, e)
        else {
          subtrees += (Right -> context.actorOf(BinaryTreeNode.props(e, initiallyRemoved = false)))
          requestor ! OperationFinished(id)
        }
      } else {
        // already exists
        removed = false
        requestor ! OperationFinished(id)
      }
    }
    case Remove(requestor, id, e) => {
      if (elem == e) {
        removed = true
        requestor ! OperationFinished(id)
      } else if (elem > e && subtrees.contains(Left)) {
        subtrees(Left) ! Remove(requestor, id, e)
      } else if (elem < e && subtrees.contains(Right)) {
        subtrees(Right) ! Remove(requestor, id, e)
      } else {
        // not found
        requestor ! OperationFinished(id)
      }
    }
    case CopyTo(newRoot) => {
      var expectingResponsesFrom = Set[ActorRef]()

      if (!removed) {
        newRoot ! Insert(self, rnd.nextInt(RND_MAX), elem)
      }

      if (subtrees.contains(Left)) {
        subtrees(Left) ! CopyTo(newRoot)
        expectingResponsesFrom += subtrees(Left)
      }
      if (subtrees.contains(Right)) {
        subtrees(Right) ! CopyTo(newRoot)
        expectingResponsesFrom += subtrees(Right)
      }

      if (!removed || expectingResponsesFrom.size > 0) {
        context.become(copying(expectingResponsesFrom, removed)) // removed flag indicates that no confirmation will occur
      } else {
        signalShutdown
      }
    }
    case CopyFinished => signalShutdown
  }

  def signalShutdown = {
    context.parent ! CopyFinished
    context.stop(self)
  }

  // optional
  /** `expected` is the set of ActorRefs whose replies we are waiting for,
    * `insertConfirmed` tracks whether the copy of this node to the new tree has been confirmed.
    */
  def copying(expected: Set[ActorRef], insertConfirmed: Boolean): Receive = {

    case OperationFinished(id) => {
      if (expected.size == 0) signalShutdown
      else context.become(copying(expected, true))
    }

    case CopyFinished => {
      if (expected.size <= 1 && insertConfirmed) context.parent ! CopyFinished
      else context.become(copying(expected.tail, insertConfirmed))
    }
  }

}
