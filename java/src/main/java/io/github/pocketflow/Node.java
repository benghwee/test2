package io.github.pocketflow;

import java.util.HashMap;
import java.util.Map;

/**
 * Base class mirroring PocketFlow's {@code Node}.
 *
 * <p>A node implements three phases:
 * <ul>
 *   <li>{@link #prep(Map)}  - read from the shared store, prepare inputs</li>
 *   <li>{@link #exec(Object)} - do the (possibly retried) work</li>
 *   <li>{@link #post(Map, Object, Object)} - write results back to the shared store</li>
 * </ul>
 *
 * <p>Nodes are chained with the {@code >>} operator which records a single
 * default successor, matching the Python implementation where each node has
 * exactly one outgoing connection.
 */
public abstract class Node {

    /** Default number of retries before giving up. */
    protected int maxRetries = 1;
    /** Seconds to wait between retries. */
    protected int wait = 0;
    /** Current retry attempt counter (0-based). Used for cache/retry decisions. */
    protected int curRetry = 0;

    private Node successor = null;

    public Node() {
    }

    public Node(int maxRetries, int wait) {
        this.maxRetries = maxRetries;
        this.wait = wait;
    }

    /** Read shared store and return data passed to {@link #exec(Object)}. */
    public abstract Object prep(Map<String, Object> shared);

    /** Perform the work. May be retried if it throws. */
    public abstract Object exec(Object prepResult);

    /** Write results back to the shared store. */
    public abstract void post(Map<String, Object> shared, Object prepResult, Object execResult);

    /**
     * Connect this node to its successor (the {@code >>} operator).
     *
     * @return the successor so chains can be written fluently
     */
    public Node connect(Node next) {
        this.successor = next;
        return next;
    }

    public Node getSuccessor() {
        return successor;
    }

    /**
     * Run the node once against the shared store with retry handling.
     */
    public Object run(Map<String, Object> shared) {
        Object prepResult = prep(shared);
        int retries = Math.max(this.maxRetries, 1);
        Object execResult = null;
        Exception lastError = null;
        for (int attempt = 0; attempt < retries; attempt++) {
            this.curRetry = attempt;
            try {
                execResult = exec(prepResult);
                lastError = null;
                break;
            } catch (Exception e) {
                lastError = e;
                System.err.println("[" + this.getClass().getSimpleName() + "] attempt "
                        + (attempt + 1) + "/" + retries + " failed: " + e.getMessage());
                if (attempt < retries - 1 && this.wait > 0) {
                    try {
                        Thread.sleep(this.wait * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        if (lastError != null) {
            throw new RuntimeException("Node " + this.getClass().getSimpleName()
                    + " failed after " + retries + " attempts", lastError);
        }
        post(shared, prepResult, execResult);
        return execResult;
    }
}
