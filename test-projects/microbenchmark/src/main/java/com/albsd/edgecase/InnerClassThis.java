package com.albsd.edgecase;

/**
 * Edge case: inner class Outer.this references.
 * Hypothesis: Outer.this becomes invalid in Kotlin — j2k either leaves it as-is
 * (compile error) or emits this@Outer correctly.
 * Expected idiomatic Kotlin: this@InnerClassThis
 */
public class InnerClassThis {

    private String name;
    private int value;

    public InnerClassThis(String name, int value) {
        this.name = name;
        this.value = value;
    }

    public class Inner {
        private String name; // shadows outer field

        public Inner(String name) {
            this.name = name;
        }

        // Must use Outer.this to reach outer fields
        public String describe() {
            return "inner=" + this.name + " outer=" + InnerClassThis.this.name;
        }

        public int getOuterValue() {
            return InnerClassThis.this.value;
        }

        // Passes outer instance explicitly
        public InnerClassThis getOuter() {
            return InnerClassThis.this;
        }
    }

    // Anonymous class that also references enclosing this
    public Runnable makeRunnable() {
        return new Runnable() {
            @Override
            public void run() {
                System.out.println(InnerClassThis.this.name + "=" + InnerClassThis.this.value);
            }
        };
    }

    // Static nested class — no Outer.this, should be fine
    public static class StaticNested {
        private final String tag;

        public StaticNested(String tag) {
            this.tag = tag;
        }

        public String getTag() { return tag; }
    }
}
