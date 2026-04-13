package com.albsd.edgecase;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Edge case: nested anonymous classes.
 * Hypothesis: j2k will not convert Comparator / Runnable anonymous classes to lambdas.
 * Expected idiomatic Kotlin: compareBy { it.length }, Runnable { ... }, or plain lambdas.
 */
public class NestedAnonymousClass {

    public List<String> sortByLength(List<String> items) {
        items.sort(new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return Integer.compare(a.length(), b.length());
            }
        });
        return items;
    }

    public void runLater(String message) {
        Runnable task = new Runnable() {
            @Override
            public void run() {
                System.out.println(message);
            }
        };
        new Thread(task).start();
    }

    // Nested anonymous class inside another anonymous class
    public Runnable nestedInNested() {
        return new Runnable() {
            @Override
            public void run() {
                Comparator<Integer> inner = new Comparator<Integer>() {
                    @Override
                    public int compare(Integer x, Integer y) {
                        return x - y;
                    }
                };
                List<Integer> nums = Arrays.asList(3, 1, 2);
                nums.sort(inner);
            }
        };
    }
}
