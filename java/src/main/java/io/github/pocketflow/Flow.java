package io.github.pocketflow;

import java.util.Map;

/**
 * Mirrors PocketFlow's {@code Flow}.
 *
 * <p>A flow starts at a given node and executes it, then follows the single
 * successor recorded by the {@code >>} operator until there are no more
 * successors. This reproduces the linear pipeline used by the tutorial flow.
 */
public class Flow {

    private final Node start;

    public Flow(Node start) {
        this.start = start;
    }

    public void run(Map<String, Object> shared) {
        Node current = start;
        while (current != null) {
            current.run(shared);
            current = current.getSuccessor();
        }
    }
}
