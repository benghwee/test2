package io.github.pocketflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mirrors PocketFlow's {@code BatchNode}.
 *
 * <p>{@link #prep(Map)} returns a list of items. {@link #exec(Object)} is then
 * invoked once per item (with retry handling per item), and the collected list
 * of results is passed to {@link #post(Map, Object, Object)}.
 */
public abstract class BatchNode extends Node {

    public BatchNode() {
    }

    public BatchNode(int maxRetries, int wait) {
        super(maxRetries, wait);
    }

    /** Return the list of items to process. */
    @Override
    public abstract List<Object> prep(Map<String, Object> shared);

    /** Process a single item. */
    @Override
    public abstract Object exec(Object item);

    /** Write the list of per-item results back to the shared store. */
    @Override
    public abstract void post(Map<String, Object> shared, Object prepResult, Object execResult);

    @Override
    public Object run(Map<String, Object> shared) {
        List<Object> items = prep(shared);
        List<Object> results = new ArrayList<>();
        for (Object item : items) {
            int retries = Math.max(this.maxRetries, 1);
            Object result = null;
            Exception lastError = null;
            for (int attempt = 0; attempt < retries; attempt++) {
                this.curRetry = attempt;
                try {
                    result = exec(item);
                    lastError = null;
                    break;
                } catch (Exception e) {
                    lastError = e;
                    System.err.println("[" + this.getClass().getSimpleName()
                            + "] item " + item + " attempt " + (attempt + 1) + "/"
                            + retries + " failed: " + e.getMessage());
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
                throw new RuntimeException("BatchNode " + this.getClass().getSimpleName()
                        + " failed after " + retries + " attempts on item " + item, lastError);
            }
            results.add(result);
        }
        post(shared, items, results);
        return results;
    }
}
