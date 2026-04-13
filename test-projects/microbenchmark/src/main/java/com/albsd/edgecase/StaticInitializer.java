package com.albsd.edgecase;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Edge case: static initializer blocks.
 * Hypothesis: converted to companion object init {} but ordering guarantees between
 * multiple static blocks and static field declarations may be lost.
 * Expected idiomatic Kotlin: companion object with val MAP = mapOf(...)
 */
public class StaticInitializer {

    // Static field initialized inline
    public static final String PREFIX = "key_";

    // Static field populated in static init block
    public static final Map<String, Integer> CODES;

    // Second static block — ordering relative to CODES matters
    public static final String FIRST_KEY;

    static {
        Map<String, Integer> m = new HashMap<>();
        m.put(PREFIX + "alpha", 1);
        m.put(PREFIX + "beta",  2);
        m.put(PREFIX + "gamma", 3);
        CODES = Collections.unmodifiableMap(m);
    }

    static {
        // Depends on CODES being fully initialized by the block above
        FIRST_KEY = CODES.keySet().iterator().next();
    }

    // Instance initializer block (not static) — should become init {}
    private final String label;

    {
        label = "instance-" + System.nanoTime();
    }

    public StaticInitializer() {}

    public String getLabel() { return label; }
}
