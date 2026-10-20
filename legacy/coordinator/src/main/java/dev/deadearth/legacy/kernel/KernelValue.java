package dev.deadearth.legacy.kernel;

import java.util.Arrays;

/** Keeps structural record equality for the Java 8 immutable value classes. */
abstract class KernelValue {
    protected abstract Object[] values();
    @Override public final boolean equals(Object other) {
        return this==other||other!=null&&getClass()==other.getClass()
            &&Arrays.deepEquals(values(),((KernelValue)other).values());
    }
    @Override public final int hashCode(){return Arrays.deepHashCode(values());}
    @Override public final String toString(){return getClass().getSimpleName()+Arrays.toString(values());}
}
