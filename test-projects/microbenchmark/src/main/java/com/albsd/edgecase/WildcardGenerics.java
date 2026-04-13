package com.albsd.edgecase;

import java.util.ArrayList;
import java.util.List;

/**
 * Edge case: complex generics with wildcards.
 * Hypothesis: type parameters will degrade to Any (or out Any? / in Nothing).
 * Expected idiomatic Kotlin: out T / in T with proper bounds preserved.
 */
public class WildcardGenerics {

    // ? extends — producer/covariant
    public double sumLengths(List<? extends CharSequence> items) {
        double total = 0;
        for (CharSequence s : items) {
            total += s.length();
        }
        return total;
    }

    // ? super — consumer/contravariant
    public void addStrings(List<? super String> sink, int count) {
        for (int i = 0; i < count; i++) {
            sink.add("item-" + i);
        }
    }

    // Nested wildcards
    public List<List<? extends Number>> groupNumbers(List<? extends Number> nums) {
        List<List<? extends Number>> result = new ArrayList<>();
        result.add(nums);
        return result;
    }

    // Unbounded wildcard
    public int countNonNull(List<?> items) {
        int count = 0;
        for (Object item : items) {
            if (item != null) count++;
        }
        return count;
    }

    // Multi-bound (not a wildcard but tests generic intersection)
    public <T extends Comparable<T> & Cloneable> T max(T a, T b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
