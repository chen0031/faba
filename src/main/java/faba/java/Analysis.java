package faba.java;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.*;
import static faba.java.AbstractValues.*;

class AbstractValues {
    static final class ParamValue extends BasicValue {
        ParamValue(Type tp) {
            super(tp);
        }
    }
    static final BasicValue InstanceOfCheckValue = new BasicValue(Type.INT_TYPE) {
        @Override
        public boolean equals(Object value) {
            return this == value;
        }
    };

    static final BasicValue TrueValue = new BasicValue(Type.INT_TYPE) {
        @Override
        public boolean equals(Object value) {
            return this == value;
        }
    };

    static final BasicValue FalseValue = new BasicValue(Type.INT_TYPE) {
        @Override
        public boolean equals(Object value) {
            return this == value;
        }
    };

    static final BasicValue NullValue = new BasicValue(Type.getObjectType("null")) {
        @Override
        public boolean equals(Object value) {
            return this == value;
        }
    };
    static final class NotNullValue extends BasicValue {
        NotNullValue(Type tp) {
            super(tp);
        }
    }
    static final class CallResultValue extends BasicValue {
        final Set<Key> inters;
        CallResultValue(Type tp, Set<Key> inters) {
            super(tp);
            this.inters = inters;
        }
    }

    static boolean isInstance(Conf curr, Conf prev) {
        if (curr.insnIndex != prev.insnIndex) {
            return false;
        }
        Frame<BasicValue> currFr = curr.frame;
        Frame<BasicValue> prevFr = prev.frame;
        for (int i = 0; i < currFr.getLocals(); i++) {
            if (!isInstance(currFr.getLocal(i), prevFr.getLocal(i))) {
                return false;
            }
        }
        for (int i = 0; i < currFr.getStackSize(); i++) {
            if (!isInstance(currFr.getStack(i), prevFr.getStack(i))) {
                return false;
            }
        }
        return true;
    }

    static boolean isInstance(BasicValue curr, BasicValue prev) {
        if (prev instanceof ParamValue) {
            return curr instanceof ParamValue;
        }
        if (InstanceOfCheckValue == prev) {
            return InstanceOfCheckValue == curr;
        }
        if (TrueValue == prev) {
            return TrueValue == curr;
        }
        if (FalseValue == prev) {
            return FalseValue == curr;
        }
        if (NullValue == prev) {
            return NullValue == curr;
        }
        if (prev instanceof NotNullValue) {
            return curr instanceof NotNullValue;
        }
        if (prev instanceof CallResultValue) {
            if (curr instanceof CallResultValue) {
                CallResultValue prevCall = (CallResultValue) prev;
                CallResultValue currCall = (CallResultValue) curr;
                return prevCall.inters.equals(currCall.inters);
            }
            else {
                return false;
            }
        }
        System.out.print("");
        return true;
    }
}

final class Conf {
    final int insnIndex;
    final Frame<BasicValue> frame;

    Conf(int insnIndex, Frame<BasicValue> frame) {
        this.insnIndex = insnIndex;
        this.frame = frame;
    }
}

final class State {
    final int index;
    final Conf conf;
    final List<Conf> history;
    final boolean taken;
    final boolean hasCompanions;
    final int insnIndex;

    State(int index, Conf conf, List<Conf> history, boolean taken, boolean hasCompanions) {
        this.index = index;
        this.conf = conf;
        this.history = history;
        this.taken = taken;
        this.hasCompanions = hasCompanions;
        insnIndex = conf.insnIndex;
    }
}

interface PendingAction<Res> {}
class ProceedState<Res> implements PendingAction<Res> {
    final State state;

    ProceedState(State state) {
        this.state = state;
    }
}
class MakeResult<Res> implements PendingAction<Res> {
    final State state;
    final Res subResult;
    final List<Integer> indices;

    // TODO - indices array
    MakeResult(State state, Res subResult, List<Integer> indices) {
        this.state = state;
        this.subResult = subResult;
        this.indices = indices;
    }
}

abstract class Analysis<Res> {
    final RichControlFlow richControlFlow;
    final Direction direction;
    final ControlFlowGraph controlFlow;
    final MethodNode methodNode;
    final Method method;
    final DFSTree dfsTree;
    final Res myIdentity;

    final Deque<PendingAction<Res>> pending = new LinkedList<PendingAction<Res>>();
    final Map<Integer, List<State>> computed = new HashMap<Integer, List<State>>();
    final Map<Integer, Res> results = new HashMap<Integer, Res>();
    final Key aKey;

    Res earlyResult = null;

    abstract Res identity();
    abstract Res combineResults(Res delta, List<Res> subResults);
    abstract boolean isEarlyResult(Res res);
    abstract Equation<Key, Value> mkEquation(Res result);
    abstract void processState(State state) throws AnalyzerException;

    protected Analysis(RichControlFlow richControlFlow, Direction direction) {
        this.richControlFlow = richControlFlow;
        this.direction = direction;
        controlFlow = richControlFlow.controlFlow;
        methodNode = controlFlow.methodNode;
        method = new Method(controlFlow.className, methodNode.name, methodNode.desc);
        dfsTree = richControlFlow.dfsTree;
        aKey = new Key(method, direction);
        myIdentity = identity();
    }

    final State createStartState() {
        return new State(0, new Conf(0, createStartFrame()), new ArrayList<Conf>(), false, false);
    }

    final boolean stateInstance(State curr, State prev) {
        if (curr.taken != prev.taken) {
            return false;
        }
        if (!isInstance(curr.conf, prev.conf)) {
            return false;
        }
        if (curr.history.size() != prev.history.size()) {
            return false;
        }
        for (int i = 0; i < curr.history.size(); i++) {
            if (!isInstance(curr.history.get(i), prev.history.get(i))) {
                return false;
            }
        }
        return true;
    }

    final Equation<Key, Value> analyze() throws AnalyzerException {
        pending.push(new ProceedState<Res>(createStartState()));
        while (!pending.isEmpty() && earlyResult == null) {
            PendingAction<Res> action = pending.pop();
            if (action instanceof MakeResult) {
                MakeResult<Res> makeResult = (MakeResult<Res>) action;
                ArrayList<Res> subResults = new ArrayList<Res>();
                for (int index : makeResult.indices) {
                    subResults.add(results.get(index));
                }
                Res result = combineResults(makeResult.subResult, subResults);
                if (isEarlyResult(result)) {
                    earlyResult = result;
                } else {
                    State state = makeResult.state;
                    int insnIndex = state.insnIndex;
                    results.put(state.index, result);
                    List<State> thisComputed = computed.get(insnIndex);
                    if (thisComputed == null) {
                        thisComputed = new ArrayList<State>();
                        computed.put(insnIndex, thisComputed);
                    }
                    thisComputed.add(state);
                }
            }
            else if (action instanceof ProceedState) {
                ProceedState<Res> proceedState = (ProceedState<Res>) action;
                State state = proceedState.state;
                int insnIndex = state.insnIndex;
                Conf conf = state.conf;
                List<Conf> history = state.history;

                boolean fold = false;
                if (dfsTree.loopEnters.contains(insnIndex)) {
                    for (Conf prev : history) {
                        if (isInstance(conf, prev)) {
                            fold = true;
                        }
                    }
                }
                if (fold) {
                    results.put(state.index, myIdentity);
                    List<State> thisComputed = computed.get(insnIndex);
                    if (thisComputed == null) {
                        thisComputed = new ArrayList<State>();
                        computed.put(insnIndex, thisComputed);
                    }
                    thisComputed.add(state);
                }
                else {
                    State baseState = null;
                    List<State> thisComputed = computed.get(insnIndex);
                    if (thisComputed != null) {
                        for (State prevState : thisComputed) {
                            if (stateInstance(state, prevState)) {
                                baseState = prevState;
                                break;
                            }
                        }
                    }
                    if (baseState != null) {
                        results.put(state.index, results.get(baseState.index));
                    } else {
                        // the main call
                        processState(state);
                    }

                }
            }
        }
        if (earlyResult != null) {
            return mkEquation(earlyResult);
        } else {
            return mkEquation(results.get(0));
        }
    }

    final Frame<BasicValue> createStartFrame() {
        Frame<BasicValue> frame = new Frame<BasicValue>(methodNode.maxLocals, methodNode.maxStack);
        Type returnType = Type.getReturnType(methodNode.desc);
        BasicValue returnValue = Type.VOID_TYPE.equals(returnType) ? null : new BasicValue(returnType);
        frame.setReturn(returnValue);

        Type[] args = Type.getArgumentTypes(methodNode.desc);
        int local = 0;
        if ((methodNode.access & Opcodes.ACC_STATIC) == 0) {
            frame.setLocal(local++, new BasicValue(Type.getObjectType(controlFlow.className)));
        }
        for (int i = 0; i < args.length; i++) {
            BasicValue value;
            if (direction instanceof InOut && ((InOut)direction).paramIndex == i) {
                value = new ParamValue(args[i]);
            }
            else if (direction instanceof In && ((In)direction).paramIndex == i) {
                value = new ParamValue(args[i]);
            }
            else {
                value = new BasicValue(args[i]);
            }
            frame.setLocal(local++, value);
            if (args[i].getSize() == 2) {
                frame.setLocal(local++, BasicValue.UNINITIALIZED_VALUE);
            }
        }
        while (local < methodNode.maxLocals) {
            frame.setLocal(local++, BasicValue.UNINITIALIZED_VALUE);
        }
        return frame;
    }

    final BasicValue popValue(Frame<BasicValue> frame) {
        return frame.getStack(frame.getStackSize() - 1);
    }

    final  <A> List<A> append(List<A> xs, A x) {
        ArrayList<A> result = new ArrayList<A>();
        if (xs != null) {
            result.addAll(xs);
        }
        result.add(x);
        return result;
    }
}

