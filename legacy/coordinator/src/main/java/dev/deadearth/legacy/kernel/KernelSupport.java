package dev.deadearth.legacy.kernel;

import java.util.*;

/** Java 8 collection/value helpers; never exposes mutable catalogue state. */
final class KernelSupport {
    private KernelSupport() {}

    @SafeVarargs
    static <T> Set<T> set(T... values) {
        return immutableSet(Arrays.asList(values));
    }

    static <T> List<T> immutableList(Collection<? extends T> values) {
        ArrayList<T> copy=new ArrayList<T>(values.size());
        for(T value:values)copy.add(Objects.requireNonNull(value));
        return Collections.unmodifiableList(copy);
    }

    static <T> Set<T> immutableSet(Collection<? extends T> values) {
        LinkedHashSet<T> copy=new LinkedHashSet<T>();
        for(T value:values)copy.add(Objects.requireNonNull(value));
        return Collections.unmodifiableSet(copy);
    }

    static <K,V> Map<K,V> immutableMap(Map<? extends K,? extends V> values) {
        LinkedHashMap<K,V> copy=new LinkedHashMap<K,V>();
        for(Map.Entry<? extends K,? extends V> entry:values.entrySet())
            copy.put(Objects.requireNonNull(entry.getKey()),Objects.requireNonNull(entry.getValue()));
        return Collections.unmodifiableMap(copy);
    }

    static int clamp(int value,int min,int max){return Math.max(min,Math.min(max,value));}
    static double clamp(double value,double min,double max){return Math.max(min,Math.min(max,value));}

    static String hex(byte[] bytes) {
        char[] digits="0123456789abcdef".toCharArray();char[] result=new char[bytes.length*2];
        for(int i=0;i<bytes.length;i++){int value=bytes[i]&255;result[2*i]=digits[value>>>4];result[2*i+1]=digits[value&15];}
        return new String(result);
    }
}
