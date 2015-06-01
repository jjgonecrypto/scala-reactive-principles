package kvstore

import akka.actor.SupervisorStrategy.Restart
import akka.actor._
import akka.pattern.{AskTimeoutException, ask}
import kvstore.Arbiter._
import scala.concurrent.Future

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object Replica {
  sealed trait Operation {
    def key: String
    def id: Long
  }
  case class Insert(key: String, value: String, id: Long) extends Operation
  case class Remove(key: String, id: Long) extends Operation
  case class Get(key: String, id: Long) extends Operation

  sealed trait OperationReply
  case class OperationAck(id: Long) extends OperationReply
  case class OperationFailed(id: Long) extends OperationReply
  case class GetResult(key: String, valueOption: Option[String], id: Long) extends OperationReply

  def props(arbiter: ActorRef, persistenceProps: Props): Props = Props(new Replica(arbiter, persistenceProps))
}

class Replica(val arbiter: ActorRef, persistenceProps: Props) extends Actor {
  import Persistence._
  import Replica._
  import Replicator._
  import context.dispatcher

  context.setReceiveTimeout(100.millis)

  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1.second) {
    case _: Exception => Restart
  }

  var kv = Map.empty[String, String]

  // a map from secondary replicas to replicators
  var secondaries = Map.empty[ActorRef, ActorRef]
  // the current set of replicators
  var replicators = Set.empty[ActorRef]

  var persistence = context.actorOf(persistenceProps)

  var curSeq = 0L

  var isPrimary = false

  // start by notifying the arbiter of a join
  arbiter ! Join

  def receive = {
    case JoinedPrimary   => {
      context.become(leader)
      isPrimary = true
    }
    case JoinedSecondary => context.become(replica)
  }

  @volatile var pendingConfirmations = Map.empty[Long, (ActorRef, Any)]

  def tryReplicateAndPersist(replicate: Replicate, persist:Persist, recipient: ActorRef) = {

    val collection = Set((persistence ? persist)(1.second).mapTo[Persisted]) ++
      replicators.map(r => (r ? replicate)(1.second).mapTo[Replicated])

    val response = Future.traverse(collection)(x => x)

    response.onComplete {
      case Success(msg) => synchronized {
        if (pendingConfirmations.contains(persist.id)) {
          recipient ! OperationAck(persist.id)
          pendingConfirmations -= persist.id
        }
      }
      case Failure(ex) => synchronized {
        if (pendingConfirmations.contains(persist.id)) {
          recipient ! OperationFailed(persist.id)
          pendingConfirmations -= persist.id
        }
      }
    }
  }

  def tryPersist(persist:Persist, recipient: ActorRef) = {
    for {
      response <- (persistence ? persist)(1.second).mapTo[Persisted].map(_ => SnapshotAck(persist.key, persist.id))
    } yield recipient ! response
  }

  def updateReceived(key: String, valueOption: Option[String], id:Long) = {
    // send this message off to all replicators
    val replicate = Replicate(key, valueOption, id)
    // and to persistence layer
    val persist = Persist(key, valueOption, id)

    tryReplicateAndPersist(replicate, persist, sender)
    pendingConfirmations += id -> (sender, (replicate, persist))
  }

  def handleRetries: Receive = {
    case ReceiveTimeout => {
      // when persistence hasn't replied, resend all
      pendingConfirmations.foreach {
        case (_, (s, (replicate:Replicate, persist:Persist))) => tryReplicateAndPersist(replicate, persist, s)
        case (_, (s, persist:Persist)) => tryPersist(persist, s)
      }
    }
  }

  // Behavior for  the leader role.
  val leader: Receive = handleRetries orElse {
    case Insert(key, value, id) => {
      kv += key -> value

      updateReceived(key, Some(value), id)
    }
    case Remove(key, id) => {
      kv -= key

      updateReceived(key, None, id)
    }
    case Get(key, id) => {
      sender ! GetResult(key, if (kv.contains(key)) Some(kv(key)) else None, id)
    }
    case Replicas(replicas) => {
      // members have changed, the members will be replicas - self - known secondaries

      // get all new secondaries and map to new Replicator
      val newSecondaries = replicas
          .filter(r => r != self && !secondaries.contains(r))
          .map(secondary => (secondary, context.actorOf(Replicator.props(secondary))))

      // now remove any unused secondaries
      secondaries.map { case (s, _) => s }.filter{ s => !replicas.contains(s) }.foreach(removeSecondary)

      // append new to local stores
      secondaries ++= newSecondaries
      replicators ++= newSecondaries.map { case (_, replicator) => replicator }

      // deathwatch all secondaries
      newSecondaries.foreach { case (secondary, _) => context.watch(secondary) }

      // finally, send update events to seed replica
      kv.foreach { case (k, v) =>
        newSecondaries.foreach { case (secondary, replicator) => replicator ! Replicate(k, Some(v), Long.MaxValue) }
      }
    }
    case Terminated(secondary) => {
      removeSecondary(secondary)
    }
  }

  def removeSecondary(secondary: ActorRef) = {
    val replicator = secondaries(secondary)

    // terminate the replicator, by instructing it to shut itself down
    replicator ! Shutdown

    // remove all references
    secondaries -= secondary
    replicators -= replicator
  }

  // Behavior for the replica role.
  val replica: Receive = handleRetries orElse {
    case Get(key, id) => {
      sender ! GetResult(key, if (kv.contains(key)) Some(kv(key)) else None, id)
    }

    case Snapshot(key, valueOption, seq) => {
      if (seq == curSeq) {

        // then implement the change
        valueOption match {
          case Some(value) => kv += key -> value
          case None => kv -= key
        }

        // try to persist
        val message = Persist(key, valueOption, seq)

        tryPersist(message, sender)

        pendingConfirmations += seq -> (sender, message)

        // and update our internal sequence counter
        curSeq += 1L

      } else if (seq < curSeq) { // earlier seq

        // ack
        sender ! SnapshotAck(key, seq)

      }
    }


  }

}

