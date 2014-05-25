package faba.java;

import java.util.*;

final class ELattice<T extends Enum<T>> {
    final T bot;
    final T top;

    ELattice(T bot, T top) {
        this.bot = bot;
        this.top = top;
    }

    final T join(T x, T y) {
        if (x == bot) return y;
        if (y == bot) return x;
        if (x == y) return x;
        return top;
    }

    final T meet(T x, T y) {
        if (x == top) return y;
        if (y == top) return x;
        if (x == y) return x;
        return bot;
    }
}

final class Component<Id> {
    final boolean touched;
    final Set<Id> ids;

    Component(boolean touched, Set<Id> ids) {
        this.touched = touched;
        this.ids = ids;
    }

    Component<Id> remove(Id id) {
        if (ids.contains(id)) {
            HashSet<Id> newIds = new HashSet<Id>(ids);
            newIds.remove(id);
            return new Component<Id>(touched, newIds);
        }
        else {
            return this;
        }
    }

    Component<Id> removeAndTouch(Id id) {
        if (ids.contains(id)) {
            HashSet<Id> newIds = new HashSet<Id>(ids);
            newIds.remove(id);
            return new Component<Id>(true, newIds);
        } else {
            return this;
        }
    }

    boolean isEmpty() {
        return ids.isEmpty();
    }

    boolean isEmptyAndTouched() {
        return ids.isEmpty() && touched;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Component component = (Component) o;

        if (touched != component.touched) return false;
        if (!ids.equals(component.ids)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = (touched ? 1 : 0);
        result = 31 * result + ids.hashCode();
        return result;
    }
}

interface Result<Id, T> {}
final class Final<Id, T> implements Result<Id, T> {
    final T value;
    Final(T value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return "Final{" +
                "value=" + value +
                '}';
    }
}

final class Solution<Id, Val> {
    final Id id;
    final Val value;

    Solution(Id id, Val value) {
        this.id = id;
        this.value = value;
    }
}

final class Pending<Id, T> implements Result<Id, T> {
    final T infinum;
    final Set<Component<Id>> delta;

    Pending(T infinum, Set<Component<Id>> delta) {
        this.infinum = infinum;
        this.delta = delta;
    }
}

final class Equation<Id, T> {
    final Id id;
    final Result<Id, T> rhs;

    Equation(Id id, Result<Id, T> rhs) {
        this.id = id;
        this.rhs = rhs;
    }
}

final class Solver<Id, Val extends Enum<Val>> {
    private final ELattice<Val> lattice;
    private final HashMap<Id, Set<Id>> dependencies = new HashMap<>();
    private final HashMap<Id, Pending<Id, Val>> pending = new HashMap<>();
    private final Queue<Solution<Id, Val>> moving = new LinkedList<>();
    private final HashMap<Id, Val> solved = new HashMap<>();

    Solver(ELattice<Val> lattice) {
        this.lattice = lattice;
    }

    Solver(ELattice<Val> lattice, List<Equation<Id, Val>> equations) {
        this.lattice = lattice;
        for (Equation<Id, Val> equation : equations) {
            addEquation(equation);
        }
    }

    void addEquation(Equation<Id, Val> equation) {
        if (equation.rhs instanceof Final) {
            Final<Id, Val> finalResult = (Final<Id, Val>) equation.rhs;
            moving.add(new Solution<>(equation.id, finalResult.value));
        }
        else if (equation.rhs instanceof Pending) {
            Pending<Id, Val> pendingResult = (Pending<Id, Val>) equation.rhs;
            if (pendingResult.infinum.equals(lattice.top)) {
                moving.add(new Solution<>(equation.id, lattice.top));
            }
            else {
                for (Component<Id> component : pendingResult.delta) {
                    for (Id trigger : component.ids) {
                        Set<Id> set = dependencies.get(trigger);
                        if (set == null) {
                            set = new HashSet<>();
                            dependencies.put(trigger, set);
                        }
                        set.add(equation.id);
                    }
                }
                pending.put(equation.id, pendingResult);
            }
        }
    }

    Map<Id, Val> solve() {
        Solution<Id, Val> sol;
        while ((sol = moving.poll()) != null) {
            solved.put(sol.id, sol.value);
            Set<Id> dIds = dependencies.remove(sol.id);
            if (dIds != null) {
                for (Id dId : dIds) {
                    Pending<Id, Val> pend = pending.remove(dId);
                    if (pend != null) {
                        Result<Id, Val> pend1 = substitute(pend, sol.id, sol.value);
                        if (pend1 instanceof Final) {
                            Final<Id, Val> fi = (Final<Id, Val>) pend1;
                            moving.add(new Solution<>(dId, fi.value));
                        }
                        else {
                            pending.put(dId, (Pending<Id, Val>) pend1);
                        }
                    }
                }
            }
        }
        pending.clear();
        return solved;
    }

    Result<Id, Val> substitute(Pending<Id, Val> pending, Id id, Val value) {
        if (value.equals(lattice.bot)) {
            HashSet<Component<Id>> delta = new HashSet<>();
            for (Component<Id> component : pending.delta) {
                if (!component.ids.contains(id)) {
                    delta.add(component);
                }
            }
            if (delta.isEmpty()) {
                return new Final<>(pending.infinum);
            }
            else {
                return new Pending<>(pending.infinum, delta);
            }
        }
        else if (value.equals(lattice.top)) {
            HashSet<Component<Id>> delta = new HashSet<>();
            for (Component<Id> component : pending.delta) {
                Component<Id> component1 = component.remove(id);
                if (!component1.isEmptyAndTouched()) {
                    if (component1.isEmpty()) {
                        return new Final<>(lattice.top);
                    } else {
                        delta.add(component1);
                    }
                }
            }
            if (delta.isEmpty()) {
                return new Final<>(pending.infinum);
            }
            else {
                return new Pending<>(pending.infinum, delta);
            }
        }
        else {
            Val infinum = lattice.join(pending.infinum, value);
            if (infinum == lattice.top) {
                return new Final<>(lattice.top);
            }
            HashSet<Component<Id>> delta = new HashSet<>();
            for (Component<Id> component : pending.delta) {
                Component<Id> component1 = component.removeAndTouch(id);
                if (!component1.isEmpty()) {
                    delta.add(component1);
                }
            }
            if (delta.isEmpty()) {
                return new Final<>(infinum);
            }
            else {
                return new Pending<>(infinum, delta);
            }
        }
    }
}