/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package actorbintree

import akka.actor._

import scala.collection.immutable.Queue

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


class BinaryTreeSet extends Actor with Stash {
  import BinaryTreeSet._
  import BinaryTreeNode._

  def createRoot: ActorRef = context.actorOf(BinaryTreeNode.props(0, initiallyRemoved = true))

  var root = createRoot

  // optional
  var pendingQueue = Queue.empty[Operation]

  // optional
  def receive = normal

  // optional
  /** Accepts `Operation` and `GC` messages. */
  val normal: Receive = {
    case operation: Operation =>
      root ! operation
    case GC =>
      val newRoot = createRoot
      root ! CopyTo(newRoot)
      context.watch(root)
      context.become(garbageCollecting(newRoot))
  }

  // optional
  /** Handles messages while garbage collection is performed.
    * `newRoot` is the root of the new binary tree where we want to copy
    * all non-removed elements into.
    */
  def garbageCollecting(newRoot: ActorRef): Receive = {
    case GC => ()
    case _: Operation => stash()
    case Terminated(_) =>
      root = newRoot
      unstashAll()
      context.become(normal)
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

  def uponEquality(foundElem: Operation => Unit)(noChild: (Operation, Position) => Unit): Receive = {
    case operation: Operation =>
      if (operation.elem == elem) foundElem(operation)
      else {
        val position: Position = if (operation.elem < elem) Left else Right
        subtrees.get(position) match {
          case Some(child) => child ! operation
          case None => noChild(operation, position)
        }
      }
  }

  // optional
  /** Handles `Operation` messages and `CopyTo` requests. */
  val normal: Receive =
    uponEquality {
      case Contains(requester, id, _) =>
        requester ! ContainsResult(id, !removed)
      case Insert(requester, id, _) =>
        removed = false
        requester ! OperationFinished(id)
      case Remove(requester, id, _) =>
        removed = true
        requester ! OperationFinished(id)
    } {
      case (Contains(requester, id, _), _) =>
        requester ! ContainsResult(id, false)
      case (Insert(requester, id, elem), pos) =>
        val child = context.actorOf(props(elem, false))
        context.watch(child)
        subtrees += pos -> child
        requester ! OperationFinished(id)
      case (Remove(requester, id, _), _) =>
        requester ! OperationFinished(id)
    } orElse {
      case CopyTo(newRoot) =>
        context.children foreach { _ ! CopyTo(newRoot) }
        if (removed) {
          context.become(copying(true))
        } else {
          newRoot ! Insert(self, -1, elem)
          context.become(copying(false))
        }
    }

  // optional
  /**
    * `insertConfirmed` tracks whether the copy of this node to the new tree has been confirmed.
    */
  def copying(insertConfirmed: Boolean): Receive = {
    if (insertConfirmed && context.children.isEmpty) context.stop(self)

    {
      case OperationFinished(_) =>
        context.become(copying(true))
      case Terminated(_) =>
        context.become(copying(insertConfirmed))
    }
  }

}
