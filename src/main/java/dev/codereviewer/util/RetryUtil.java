package dev.codereviewer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

/**
 * Generic retry utility with exponential backoff and jitter.
 *
 * <p>Used by LLM clients and GitHub API calls to handle transient failures
 * (rate limits, network blips, server errors).
 */
public final class RetryUtil {

    private static final Logger LOG = LoggerFactory.getLogger(RetryUtil.class);

    private RetryUtil() {
        // utility class
    }

    /**
     * Executes the given callable with retry logic.
     *
     * @param action       descriptive name for logging (e.g., "Gemini API call")
     * @param callable     the operation to attempt
     * @param maxAttempts  maximum number of attempts (including the first)
     * @param baseDelay    base delay between retries (doubles each attempt)
     * @param retryIf      predicate that returns true if the exception is retryable
     * @return the result of the callable
     * @throws Exception if all attempts are exhausted or a non-retryable exception occurs
     */
    public static <T> T withRetry(
            String action,
            Callable<T> callable,
            int maxAttempts,
            Duration baseDelay,
            Predicate<Exception> retryIf) throws Exception {

        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return callable.call();
            } catch (Exception e) {
                lastException = e;

                if (!retryIf.test(e)) {
                    LOG.error("{} failed with non-retryable error on attempt {}/{}: {}",
                            action, attempt, maxAttempts, e.getMessage());
                    throw e;
                }

                if (attempt == maxAttempts) {
                    LOG.error("{} failed after {} attempts. Last error: {}",
                            action, maxAttempts, e.getMessage());
                    break;
                }

                // Exponential backoff with jitter
                long delayMs = baseDelay.toMillis() * (1L << (attempt - 1));
                long jitter = (long) (delayMs * 0.2 * Math.random());
                long totalDelay = delayMs + jitter;

                LOG.warn("{} attempt {}/{} failed ({}), retrying in {}ms...",
                        action, attempt, maxAttempts, e.getMessage(), totalDelay);

                try {
                    Thread.sleep(totalDelay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }

        throw lastException;
    }

    /**
     * Convenience overload that retries on all exceptions.
     */
    public static <T> T withRetry(
            String action,
            Callable<T> callable,
            int maxAttempts,
            Duration baseDelay) throws Exception {
        return withRetry(action, callable, maxAttempts, baseDelay, e -> true);
    }
}
