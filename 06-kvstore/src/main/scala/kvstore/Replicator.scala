package kvstore

import akka.actor._
import akka.dispatch.sysmsg.Supervise
import scala.concurrent.duration._

object Replicator {
  case class Replicate(key: String, valueOption: Option[String], id: Long)
  case class Replicated(key: String, id: Long)

  case object Shutdown

  case class Snapshot(key: String, valueOption: Option[String], seq: Long)
  case class SnapshotAck(key: String, seq: Long)

  def props(replica: ActorRef): Props = Props(new Replicator(replica))
}

class Replicator(val replica: ActorRef) extends Actor {
  import Replicator._
  import Replica._
  import context.dispatcher

  context.setReceiveTimeout(100.milliseconds)

  // map from sequence number to pair of sender and request
  var acks = Map.empty[Long, (ActorRef, Replicate)]

  // a sequence of not-yet-sent snapshots (you can disregard this if not implementing batching)
//  var pending = Vector.empty[Snapshot]
  
  var _seqCounter = 0L
  def nextSeq = {
    val ret = _seqCounter
    _seqCounter += 1
    ret
  }

  // Behavior for the Replicator.
  def receive: Receive = {
    case Replicate(key, valueOption, id) => {

      val seq = nextSeq

      // send snapshot
      replica ! Snapshot(key, valueOption, seq)

      // track ack
      acks += seq -> (sender, Replicate(key, valueOption, id))
    }

    case SnapshotAck(key, seq) => {
      acks(seq) match {
        case (s, Replicate(key, _, id)) => {
          // remove sequence from list
          acks -= seq

          // notify replication success
          s ! Replicated(key, id)
        }
      }
    }

    case ReceiveTimeout => {
      // resend all waiting acks
      acks.foreach { case (seq, (_, Replicate(key, valueOption, _))) => replica ! Snapshot(key, valueOption, seq) }
    }

    case Shutdown => {
      acks.foreach {
        case (_, (s, Replicate(key, _, id))) =>
          s ! Replicated(key, id)
      }
      context.stop(self)
    }
  }
}
