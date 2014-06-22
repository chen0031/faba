package faba.analysis

import scala.collection.mutable
import scala.collection.immutable.IntMap

import org.objectweb.asm.tree.analysis.{BasicValue, Frame}
import org.objectweb.asm.{Opcodes, Type}

import faba.cfg._
import faba.data._
import faba.engine._

case class ParamValue(tp: Type) extends BasicValue(tp)
case class InstanceOfCheckValue() extends BasicValue(Type.INT_TYPE)
case class TrueValue() extends BasicValue(Type.INT_TYPE)
case class FalseValue() extends BasicValue(Type.INT_TYPE)
case class NullValue() extends BasicValue(Type.getObjectType("null"))
case class NotNullValue(tp: Type) extends BasicValue(tp)
case class CallResultValue(tp: Type, inters: Set[Key]) extends BasicValue(tp)

case class Conf(insnIndex: Int, frame: Frame[BasicValue])

case class State(index: Int, conf: Conf, history: List[Conf], taken: Boolean, hasCompanions: Boolean) {
  val insnIndex: Int = conf.insnIndex
  override def toString = insnIndex.toString
}

object Counter {
  var processed: Long = 0
  var nonLocalDriving: Long = 0
  var nonShared: Long = 0
  var shared: Long = 0
  var effectivelyShared: Long = 0
}

abstract class Analysis[Res] {

  sealed trait PendingAction
  case class ProceedState(state: State) extends PendingAction
  case class MakeResult(state: State, subResult: Res, indices: List[Int]) extends PendingAction

  val richControlFlow: RichControlFlow
  val direction: Direction
  val stable: Boolean
  def identity: Res
  def processState(state: State): Unit
  def isEarlyResult(res: Res): Boolean
  def combineResults(delta: Res, subResults: List[Res]): Res
  def mkEquation(result: Res): Equation[Key, Value]

  val controlFlow = richControlFlow.controlFlow
  val methodNode = controlFlow.methodNode
  val method = Method(controlFlow.className, methodNode.name, methodNode.desc)
  val dfsTree = richControlFlow.dfsTree
  val aKey = Key(method, direction, stable)

  final def createStartState(): State = State(0, Conf(0, createStartFrame()), Nil, false, false)
  final def confInstance(curr: Conf, prev: Conf): Boolean = Utils.isInstance(curr, prev)

  final def stateEquiv(curr: State, prev: State): Boolean =
    curr.taken == prev.taken &&
      Utils.equiv(curr.conf, prev.conf) &&
      curr.history.size == prev.history.size &&
      (curr.history, prev.history).zipped.forall(Utils.equiv)

  val pending = mutable.Stack[PendingAction]()
  // the key is insnIndex
  var computed = IntMap[List[State]]().withDefaultValue(Nil)
  // the key is stateIndex
  var results = IntMap[Res]()

  var earlyResult: Option[Res] = None

  final def analyze(): Equation[Key, Value] = {
    pending.push(ProceedState(createStartState()))

    while (pending.nonEmpty && earlyResult.isEmpty) pending.pop() match {
      case MakeResult(state, delta, subIndices) =>
        val result = combineResults(delta, subIndices.map(results))
        if (isEarlyResult(result)) {
          earlyResult = Some(result)
        } else {
          val insnIndex = state.insnIndex
          results = results + (state.index -> result)
          computed = computed.updated(insnIndex, state :: computed(insnIndex))
        }
      case ProceedState(state) =>
        val insnIndex = state.insnIndex
        val shared = richControlFlow.isSharedInstruction(insnIndex)
        val conf = state.conf
        val history = state.history

        val loopEnter = dfsTree.loopEnters(insnIndex)
        val fold = loopEnter && history.exists(prevConf => confInstance(conf, prevConf))

        if (fold) {
          results = results + (state.index -> identity)
          if (shared)
            computed = computed.updated(insnIndex, state :: computed(insnIndex))
        } else computed(insnIndex).find(prevState => stateEquiv(state, prevState)) match {
          case Some(ps) =>
            Counter.effectivelyShared += 1
            val insnNode = methodNode.instructions.get(insnIndex)
            results = results + (state.index -> results(ps.index))
          case None =>
            processState(state)
        }
    }

    mkEquation(earlyResult.getOrElse(results(0)))
  }

  final def createStartFrame(): Frame[BasicValue] = {
    val frame = new Frame[BasicValue](methodNode.maxLocals, methodNode.maxStack)
    val returnType = Type.getReturnType(methodNode.desc)
    val returnValue = if (returnType == Type.VOID_TYPE) null else new BasicValue(returnType)
    frame.setReturn(returnValue)

    val args = Type.getArgumentTypes(methodNode.desc)
    var local = 0
    if ((methodNode.access & Opcodes.ACC_STATIC) == 0) {
      val basicValue = new NotNullValue(Type.getObjectType(controlFlow.className))
      frame.setLocal(local, basicValue)
      local += 1
    }
    for (i <- 0 until args.size) {
      val value = direction match {
        case InOut(`i`, _) =>
          new ParamValue(args(i))
        case In(`i`) =>
          new ParamValue(args(i))
        case _ =>
          new BasicValue(args(i))
      }
      frame.setLocal(local, value)
      local += 1
      if (args(i).getSize == 2) {
        frame.setLocal(local, BasicValue.UNINITIALIZED_VALUE)
        local += 1
      }
    }
    while (local < methodNode.maxLocals) {
      frame.setLocal(local, BasicValue.UNINITIALIZED_VALUE)
      local += 1
    }
    frame
  }

  final def popValue(frame: Frame[BasicValue]): BasicValue =
    frame.getStack(frame.getStackSize - 1)
}

object Utils {
  def isInstance(curr: Conf, prev: Conf): Boolean = {
    if (curr.insnIndex != prev.insnIndex) {
      return false
    }
    val currFr = curr.frame
    val prevFr = prev.frame
    for (i <- 0 until currFr.getLocals if !isInstance(currFr.getLocal(i), prevFr.getLocal(i)))
      return false
    for (i <- 0 until currFr.getStackSize if !isInstance(currFr.getStack(i), prevFr.getStack(i)))
      return false
    true
  }

  def isInstance(curr: BasicValue, prev: BasicValue): Boolean = prev match {
    case (_: ParamValue) => curr match {
      case _: ParamValue => true
      case _ => false
    }
    case InstanceOfCheckValue() => curr match {
      case InstanceOfCheckValue() => true
      case _ => false
    }
    case TrueValue() => curr match {
      case TrueValue() => true
      case _ => false
    }
    case FalseValue() => curr match {
      case FalseValue() => true
      case _ => false
    }
    case NullValue() => curr match {
      case NullValue() => true
      case _ => false
    }
    case NotNullValue(_) => curr match {
      case NotNullValue(_) => true
      case _ => false
    }
    case CallResultValue(_, prevInters) => curr match {
      case CallResultValue(_, currInters) => currInters == prevInters
      case _ => false
    }
    case _: BasicValue => true
  }

  def equiv(curr: Conf, prev: Conf): Boolean = {
    if (curr.insnIndex != prev.insnIndex) {
      return false
    }
    val currFr = curr.frame
    val prevFr = prev.frame
    for (i <- 0 until currFr.getLocals if !equiv(currFr.getLocal(i), prevFr.getLocal(i)))
      return false
    for (i <- 0 until currFr.getStackSize if !equiv(currFr.getStack(i), prevFr.getStack(i)))
      return false
    true
  }

  def equiv(curr: BasicValue, prev: BasicValue): Boolean = {
    val result = equiv0(curr, prev)
    val result2 = equiv0(prev, curr)
    require(result == result2, s"$result != $result2 || $curr (${curr.getClass}}), $prev (${prev.getClass}})")
    result
  }

  def equiv0(curr: BasicValue, prev: BasicValue): Boolean =
    if (curr.getClass == prev.getClass) {
      (curr, prev) match {
        case (CallResultValue(_, k1), CallResultValue(_, k2)) =>
          k1 == k2
        case _ =>
          true
      }
    }
    else false

}
