package faba.java;

final class MethodExtra {
    final String signature;
    final int access;

    MethodExtra(String signature, int access) {
        this.signature = signature;
        this.access = access;
    }
}

final class Method {
    final String internalClassName;
    final String methodName;
    final String methodDesc;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Method method = (Method) o;
        return internalClassName.equals(method.internalClassName) && methodDesc.equals(method.methodDesc) && methodName.equals(method.methodName);
    }

    @Override
    public int hashCode() {
        int result = internalClassName.hashCode();
        result = 31 * result + methodName.hashCode();
        result = 31 * result + methodDesc.hashCode();
        return result;
    }

    Method(String internalClassName, String methodName, String methodDesc) {
        this.internalClassName = internalClassName;
        this.methodName = methodName;
        this.methodDesc = methodDesc;
    }

    @Override
    public String toString() {
        return "Method(" +
                internalClassName + ',' +
                methodName + ',' +
                methodDesc +
                ')';
    }
}

enum Value {
    Bot, NotNull, Null, True, False, Top
}

interface Direction {}
final class In implements Direction {
    final int paramIndex;

    In(int paramIndex) {
        this.paramIndex = paramIndex;
    }

    @Override
    public String toString() {
        return "In(" + paramIndex + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        In in = (In) o;
        if (paramIndex != in.paramIndex) return false;
        return true;
    }

    @Override
    public int hashCode() {
        return paramIndex;
    }
}

final class InOut implements Direction {
    final int paramIndex;
    final Value inValue;

    InOut(int paramIndex, Value inValue) {
        this.paramIndex = paramIndex;
        this.inValue = inValue;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        InOut inOut = (InOut) o;

        if (paramIndex != inOut.paramIndex) return false;
        if (inValue != inOut.inValue) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = paramIndex;
        result = 31 * result + inValue.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "InOut(" +
                paramIndex +
                ", " + inValue +
                ')';
    }
}

final class Out implements Direction {
    @Override
    public String toString() {
        return "Out";
    }

    @Override
    public int hashCode() {
        return 1;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Out;
    }
}

final class Key {
    final Method method;
    final Direction direction;

    Key(Method method, Direction direction) {
        this.method = method;
        this.direction = direction;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Key key = (Key) o;

        if (!direction.equals(key.direction)) return false;
        if (!method.equals(key.method)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = method.hashCode();
        result = 31 * result + direction.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "Key{" +
                "method=" + method +
                ", direction=" + direction +
                '}';
    }
}


