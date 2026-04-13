package com.albsd.edgecase;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Edge case: Java streams that should become Kotlin sequences / collection ops.
 * Hypothesis: j2k leaves stream chains as-is, keeping java.util.stream imports
 * instead of converting to .asSequence(), .filter { }, .map { }, etc.
 * Expected idiomatic Kotlin: list.filter { }.map { }.groupBy { }
 */
public class JavaStreams {

    // Simple filter + map — should become .filter { }.map { }
    public List<String> upperCaseNonEmpty(List<String> items) {
        return items.stream()
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .collect(Collectors.toList());
    }

    // groupingBy — should become .groupBy { }
    public Map<Integer, List<String>> groupByLength(List<String> items) {
        return items.stream()
                .collect(Collectors.groupingBy(String::length));
    }

    // reduce — should become .reduce or .fold
    public int sumLengths(List<String> items) {
        return items.stream()
                .mapToInt(String::length)
                .sum();
    }

    // flatMap — should become .flatMap { }
    public List<Character> allChars(List<String> items) {
        return items.stream()
                .flatMap(s -> s.chars().mapToObj(c -> (char) c))
                .collect(Collectors.toList());
    }

    // Optional chaining — should become ?.let { } or Elvis
    public String firstMatch(List<String> items, String prefix) {
        Optional<String> found = items.stream()
                .filter(s -> s.startsWith(prefix))
                .findFirst();
        return found.orElse("none");
    }

    // Stream.of + sorted + distinct
    public List<Integer> dedupSorted(Integer... values) {
        return Stream.of(values)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }
}
