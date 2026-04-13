package com.albsd.edgecase;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Edge case: @FunctionalInterface and SAM conversions.
 * Hypothesis: j2k does not convert anonymous implementations of @FunctionalInterface
 * to SAM constructor syntax or plain lambdas.
 * Expected idiomatic Kotlin: val t = Transform { s -> s.uppercase() }
 */
public class FunctionalInterfaceSam {

    @FunctionalInterface
    public interface Transform {
        String apply(String input);
    }

    @FunctionalInterface
    public interface Validator<T> {
        boolean validate(T value);
    }

    // Passing a @FunctionalInterface as anonymous class — should become SAM
    public String transform(String s) {
        Transform upper = new Transform() {
            @Override
            public String apply(String input) {
                return input.toUpperCase();
            }
        };
        return upper.apply(s);
    }

    // Using java.util.function types as anonymous classes
    public boolean check(int value) {
        Predicate<Integer> positive = new Predicate<Integer>() {
            @Override
            public boolean test(Integer i) {
                return i > 0;
            }
        };
        return positive.test(value);
    }

    // Chained functional usage
    public String pipeline(String input) {
        Function<String, String> trim = new Function<String, String>() {
            @Override
            public String apply(String s) { return s.trim(); }
        };
        Function<String, String> lower = new Function<String, String>() {
            @Override
            public String apply(String s) { return s.toLowerCase(); }
        };
        BiFunction<String, String, String> concat = new BiFunction<String, String, String>() {
            @Override
            public String apply(String a, String b) { return a + b; }
        };
        return concat.apply(trim.apply(input), lower.apply(input));
    }
}
