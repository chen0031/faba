package faba

import faba.analysis._
import faba.asm._
import faba.asm.nullableResult.NullableResultAnalysis
import faba.combined.CombinedSingleAnalysis
import faba.contracts._
import faba.data._
import faba.engine._
import faba.parameters._
import faba.source._

import org.objectweb.asm.Opcodes._
import org.objectweb.asm._
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.analysis.{Frame, Value => ASMValue}

import scala.language.existentials

/**
 * Default faba processor. A lot of fine-grained method to override.
 **/
trait FabaProcessor extends Processor {

  /**
   * Setting for benchmarking.
   * If [[idle]] is true, then FABA doesn't solve equations.
   * May be useful to set [[idle]] to true when you would like to measure/optimize performance of analysis.
   */
  @inline
  final val idle = false

  var extras = Map[Method, MethodExtra]()
  var complexTime: Long = 0
  var nonCycleTime: Long = 0
  var cycleTime: Long = 0
  var nonCycleMethods: Long = 0
  var cycleMethods: Long = 0
  var simpleTime: Long = 0
  var complexMethods: Long = 0
  var simpleMethods: Long = 0

  def handleHierarchy(access: Int, thisName: String, superName: String) {}

  override def processClass(classReader: ClassReader): Unit =
    classReader.accept(new ClassVisitor(ASM5) {
      var stableClass = false
      override def visit(version: Int, access: Int, name: String, signature: String, superName: String, interfaces: Array[String]) {
        // or there are no subclasses??
        stableClass = (access & ACC_FINAL) != 0
        super.visit(version, access, name, signature, superName, interfaces)
        handleHierarchy(access, classReader.getClassName, superName)
      }

      override def visitMethod(access: Int, name: String, desc: String, signature: String, exceptions: Array[String]) = {
        val node = new MethodNode(ASM5, access, name, desc, signature, exceptions)
        new MethodVisitor(ASM5, node) {
          var jsr = false
          override def visitEnd(): Unit = {
            super.visitEnd()
            processMethod(classReader.getClassName, node, stableClass, jsr)
          }

          override def visitJumpInsn(opcode: Int, label: Label) {
            if (opcode == Opcodes.JSR)
              jsr = true
            super.visitJumpInsn(opcode, label)
          }
        }
      }
    }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)

  def processMethod(className: String, methodNode: MethodNode, stableClass: Boolean, jsr: Boolean) {
    val argumentTypes = Type.getArgumentTypes(methodNode.desc)
    val resultType = Type.getReturnType(methodNode.desc)
    val resultSort = resultType.getSort

    val isReferenceResult = resultSort == Type.OBJECT || resultSort == Type.ARRAY
    val isBooleanResult = Type.BOOLEAN_TYPE == resultType

    val method = Method(className, methodNode.name, methodNode.desc)
    val acc = methodNode.access
    val stable = stableClass || (methodNode.name == "<init>") ||
      (acc & ACC_FINAL) != 0 || (acc & ACC_PRIVATE) != 0 || (acc & ACC_STATIC) != 0

    if (!idle)
      extras = extras.updated(method, MethodExtra(Option(methodNode.signature), methodNode.access))

    handlePurityEquation(purityEquation(method, methodNode, stable))

    if (argumentTypes.length == 0 && !(isReferenceResult || isBooleanResult)) {
      return
    }

    var added = false
    val graph = buildCFG(className, methodNode, jsr)

    if (graph.transitions.nonEmpty) {
      val dfs = buildDFSTree(graph.transitions)
      val complex = dfs.back.nonEmpty || graph.transitions.exists(_.size > 1)
      val start = System.nanoTime()
      if (complex) {
        val reducible = dfs.back.isEmpty || isReducible(graph, dfs)
        if (reducible) {
          handleComplexMethod(method, className, methodNode, dfs, argumentTypes, graph, isReferenceResult, isBooleanResult, stable, jsr)
          added = true
        }
      } else {
        handleSimpleMethod(method, argumentTypes, graph, isReferenceResult, isBooleanResult, stable)
        added = true
      }
      val time = System.nanoTime() - start
      if (complex) {
        complexMethods += 1
        complexTime += time
      }
      else {
        simpleMethods += 1
        simpleTime += time
      }
    }


    if (!added) {
      for (i <- argumentTypes.indices) {
        val argType = argumentTypes(i)
        val argSort = argType.getSort
        val isReferenceArg = argSort == Type.OBJECT || argSort == Type.ARRAY
        if (isReferenceArg) {
          handleNotNullParamEquation(Equation(Key(method, In(i), stable), Final(Values.Top)))
          if (isReferenceResult || isBooleanResult) {
            handleNullContractEquation(Equation(Key(method, InOut(i, Values.Null), stable), Final(Values.Top)))
            handleNotNullContractEquation(Equation(Key(method, InOut(i, Values.NotNull), stable), Final(Values.Top)))
          }
        }
      }
      if (isReferenceResult) {
        handleOutContractEquation(Equation(Key(method, Out, stable), Final(Values.Top)))
        handleNullableResultEquation(Equation(Key(method, Out, stable), Final(Values.Bot)))
      }
    }
  }

  def handleSimpleMethod(method: Method,
                         argumentTypes: Array[Type],
                         graph: ControlFlowGraph,
                         isReferenceResult: Boolean,
                         isBooleanResult: Boolean,
                         stable: Boolean) {
    val analyzer = new CombinedSingleAnalysis(method, graph)
    analyzer.analyze()
    if (isReferenceResult) {
      handleOutContractEquation(analyzer.outContractEquation(stable))
      handleNullableResultEquation(analyzer.nullableResultEquation(stable))
    }
    for (i <- argumentTypes.indices) {
      val argSort = argumentTypes(i).getSort
      val isReferenceArg = argSort == Type.OBJECT || argSort == Type.ARRAY
      if (isReferenceArg) {
        handleNotNullParamEquation(analyzer.notNullParamEquation(i, stable))
        handleNullableParamEquation(analyzer.nullableParamEquation(i, stable))
        // contracts
        if (isReferenceResult || isBooleanResult) {
          handleNullContractEquation(analyzer.contractEquation(i, Values.Null, stable))
          handleNotNullContractEquation(analyzer.contractEquation(i, Values.NotNull, stable))
        }
      }
    }
  }

  def handleComplexMethod(method: Method,
                          className: String,
                          methodNode: MethodNode,
                          dfs: DFSTree,
                          argumentTypes: Array[Type],
                          graph: ControlFlowGraph,
                          isReferenceResult: Boolean,
                          isBooleanResult: Boolean,
                          stable: Boolean,
                          jsr: Boolean) {
    val start = System.nanoTime()
    val cycle = dfs.back.nonEmpty
    // leaking params will be taken for
    lazy val leaking = leakingParameters(className, methodNode, jsr)
    lazy val resultOrigins = buildResultOrigins(className, methodNode, leaking.frames, graph)
    lazy val influence = ResultInfluence.analyze(methodNode, leaking, resultOrigins)
    val richControlFlow = RichControlFlow(graph, dfs)

    // todo - do we need equations for boolean results?
    lazy val resultEquation: Equation[Key, Value] = outContractEquation(richControlFlow, resultOrigins, stable)
    if (isReferenceResult) {
      handleOutContractEquation(resultEquation)
      handleNullableResultEquation(nullableResultEquation(className, methodNode, method, resultOrigins, stable, jsr))
    }
    for (i <- argumentTypes.indices) {
      val argType = argumentTypes(i)
      val argSort = argType.getSort
      val isReferenceArg = argSort == Type.OBJECT || argSort == Type.ARRAY

      if (isReferenceArg) {

        // have we discovered that param is @NotNull now?
        var notNullParam = false
        // an execution path was discovered at which this param is dereferenced
        var dereferenceFound = false

        // [[[ parameter analysis
        if (leaking.parameters(i)) {
          val (notNullParamEq, npe) = notNullParamEquation(richControlFlow, i, stable)
          notNullParam = notNullParamEq.rhs == Final(Values.NotNull)
          if (notNullParam || npe) {
            dereferenceFound = true
          }
          handleNotNullParamEquation(notNullParamEq)
        }
        else
          handleNotNullParamEquation(Equation(Key(method, In(i), stable), Final(Values.Top)))

        if (leaking.nullableParameters(i)) {
          if (dereferenceFound) {
            handleNullableParamEquation(Equation(Key(method, In(i), stable), Final(Values.Top)))
          }
          else {
            val nullableParamEq = nullableParamEquation(richControlFlow, i, stable)
            if (nullableParamEq.rhs == Final(Values.Top)) {
              dereferenceFound = true
            }
            handleNullableParamEquation(nullableParamEq)
          }
        }
        else
          handleNullableParamEquation(Equation(Key(method, In(i), stable), Final(Values.Null)))
        // ]]] parameter analysis

        // [[[ contract analysis
        if (isReferenceResult || isBooleanResult) {
          if (stable) {
            val paramInfluence = leaking.splittingParameters(i) || influence(i)
            // result may depend on a parameter
            if (leaking.parameters(i)) {
              val unconditionalDereference = dereferenceFound && !leaking.splittingParameters(i) && !resultOrigins.parameters(i)
              // [[[ null->... analysis
              if (notNullParam) {
                handleNullContractEquation(Equation(Key(method, InOut(i, Values.Null), stable), Final(Values.Bot)))
              } else if (unconditionalDereference) {
                handleNullContractEquation(Equation(Key(method, InOut(i, Values.NotNull), stable), resultEquation.rhs))
              } else if (paramInfluence) {
                handleNullContractEquation(nullContractEquation(richControlFlow, resultOrigins, i, stable))
              } else {
                // no influence - result is the same as the main equation
                handleNullContractEquation(Equation(Key(method, InOut(i, Values.NotNull), stable), resultEquation.rhs))
              }
              // ]]] null->... analysis

              // [[[ !null -> analysis
              if (paramInfluence) {
                handleNotNullContractEquation(notNullContractEquation(richControlFlow, resultOrigins, i, stable))
              } else {
                handleNotNullContractEquation(Equation(Key(method, InOut(i, Values.NotNull), stable), resultEquation.rhs))
              }
            }
            // not leaking - approximating it by out equation
            else {
              handleNullContractEquation(Equation(Key(method, InOut(i, Values.Null), stable), resultEquation.rhs))
              handleNotNullContractEquation(Equation(Key(method, InOut(i, Values.NotNull), stable), resultEquation.rhs))
            }
          }
          else {
            handleNullContractEquation(Equation(Key(method, InOut(i, Values.Null), stable), Final(Values.Top)))
            handleNotNullContractEquation(Equation(Key(method, InOut(i, Values.NotNull), stable), resultEquation.rhs))
          }
        }
        // ]]] contract analysis
      }

    }
    val time = System.nanoTime() - start
    if (cycle) {
      cycleMethods += 1
      cycleTime += time
    } else {
      nonCycleMethods += 1
      nonCycleTime += time
    }
  }

  def buildCFG(className: String, methodNode: MethodNode, jsr: Boolean): ControlFlowGraph =
    controlFlow.buildControlFlowGraph(className, methodNode, jsr)

  // build other result origins
  def buildResultOrigins(className: String, methodNode: MethodNode, frames: Array[Frame[ParamsValue]], graph: ControlFlowGraph): Origins =
    OriginsAnalysis.resultOrigins(frames.asInstanceOf[Array[Frame[ASMValue]]], methodNode, graph)

  def buildDFSTree(transitions: Array[List[Int]]): DFSTree =
    controlFlow.buildDFSTree(transitions)

  def isReducible(graph: ControlFlowGraph, dfs: DFSTree): Boolean =
    controlFlow.reducible(graph, dfs)

  def purityEquation(method: Method, methodNode: MethodNode, stable: Boolean): Equation[Key, Value] =
    PurityAnalysis.analyze(method, methodNode, stable)

  def notNullParamEquation(richControlFlow: RichControlFlow, i: Int, stable: Boolean): (Equation[Key, Value], Boolean) = {
    val analyser = new NotNullInAnalysis(richControlFlow, In(i), stable)
    try {
      val eq = analyser.analyze()
      (eq, analyser.npe)
    } catch {
      case _: LimitReachedException =>
        (Equation(analyser.aKey, Final(Values.Top)), analyser.npe)
    }
  }

  def nullableParamEquation(richControlFlow: RichControlFlow, i: Int, stable: Boolean): Equation[Key, Value] = {
    val analyser = new NullableInAnalysis(richControlFlow, In(i), stable)
    try {
      analyser.analyze()
    } catch {
      case _: LimitReachedException =>
        Equation(analyser.aKey, Final(Values.Top))
    }
  }

  def notNullContractEquation(richControlFlow: RichControlFlow, resultOrigins: Origins, i: Int, stable: Boolean): Equation[Key, Value] = {
    val analyser = new InOutAnalysis(richControlFlow, InOut(i, Values.NotNull), resultOrigins, stable)
    try {
      analyser.analyze()
    } catch {
      case _: LimitReachedException =>
        Equation(analyser.aKey, Final(Values.Top))
    }
  }

  def nullContractEquation(richControlFlow: RichControlFlow, resultOrigins: Origins, i: Int, stable: Boolean): Equation[Key, Value] = {
    val analyser = new InOutAnalysis(richControlFlow, InOut(i, Values.Null), resultOrigins, stable)
    try {
      analyser.analyze()
    } catch {
      case _: LimitReachedException =>
        Equation(analyser.aKey, Final(Values.Top))
    }
  }

  def outContractEquation(richControlFlow: RichControlFlow, resultOrigins: Origins, stable: Boolean): Equation[Key, Value] = {
    val analyser = new InOutAnalysis(richControlFlow, Out, resultOrigins, stable)
    try {
      analyser.analyze()
    } catch {
      case _: LimitReachedException =>
        Equation(analyser.aKey, Final(Values.Top))
    }
  }

  def nullableResultEquation(className: String, methodNode: MethodNode, method: Method, origins: Origins, stable: Boolean, jsr: Boolean): Equation[Key, Value] =
    Equation(Key(method, Out, stable), NullableResultAnalysis.analyze(className, methodNode, origins.instructions, jsr))

  def handlePurityEquation(eq: Equation[Key, Value]): Unit = ()
  def handleNotNullParamEquation(eq: Equation[Key, Value]): Unit = ()
  def handleNullableParamEquation(eq: Equation[Key, Value]): Unit = ()
  def handleNotNullContractEquation(eq: Equation[Key, Value]): Unit = ()
  def handleNullContractEquation(eq: Equation[Key, Value]): Unit = ()
  def handleOutContractEquation(eq: Equation[Key, Value]): Unit = ()
  def handleNullableResultEquation(eq: Equation[Key, Value]): Unit = ()

  def leakingParameters(className: String, methodNode: MethodNode, jsr: Boolean) =
    LeakingParameters.build(className, methodNode, jsr)
}
