package dev.codereviewer.llm;

import dev.codereviewer.util.RetryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * Routes LLM requests across multiple providers with automatic failover.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Try the primary client (Gemini) with retry on transient errors</li>
 *   <li>On persistent failure or rate limiting → fall back to secondary (Groq)</li>
 *   <li>If both fail → return null (caller handles gracefully)</li>
 * </ol>
 *
 * <p>This design means a single Gemini rate limit doesn't kill the entire
 * review run — the Action continues with Groq for remaining files.
 */
public class LlmRouter {

    private static final Logger LOG = LoggerFactory.getLogger(LlmRouter.class);

    private static final int MAX_RETRIES = 3;
    private static final Duration RETRY_BASE_DELAY = Duration.ofSeconds(2);

    private final LlmClient primary;
    private final LlmClient fallback;   // nullable

    /**
     * Tracks whether the primary client has been rate-limited in this run,
     * so we skip straight to fallback for remaining files.
     */
    private volatile boolean primaryRateLimited = false;
    private volatile String lastError;

    public LlmRouter(LlmClient primary, LlmClient fallback) {
        this.primary = primary;
        this.fallback = fallback;

        LOG.info("LLM router initialized — primary: {}, fallback: {}",
                primary.getName(),
                fallback != null && fallback.isAvailable() ? fallback.getName() : "none");
    }

    public String getLastError() {
        return lastError;
    }

    /**
     * Sends a review request to the best available LLM.
     *
     * @param systemPrompt the system prompt
     * @param userPrompt   the user prompt (file-specific review request)
     * @return the LLM's response text (expected JSON), or null if all providers fail
     */
    public String chat(String systemPrompt, String userPrompt) {
        // If primary was rate-limited earlier in this run, skip to fallback
        if (!primaryRateLimited && primary.isAvailable()) {
            try {
                String result = RetryUtil.withRetry(
                        primary.getName(),
                        () -> primary.chat(systemPrompt, userPrompt),
                        MAX_RETRIES,
                        RETRY_BASE_DELAY,
                        e -> e instanceof LlmClient.LlmException le && le.isRetryable()
                );
                LOG.info("Review completed via {}", primary.getName());
                return result;
            } catch (Exception e) {
                this.lastError = primary.getName() + " failed: " + e.getMessage();
                LOG.error("{} failed: {}", primary.getName(), e.getMessage());

                if (e instanceof LlmClient.LlmException le && le.getHttpStatus() == 429) {
                    primaryRateLimited = true;
                    LOG.warn("{} rate-limited — switching to fallback for remaining files",
                            primary.getName());
                }
            }
        }

        // Try fallback
        if (fallback != null && fallback.isAvailable()) {
            try {
                String result = RetryUtil.withRetry(
                        fallback.getName(),
                        () -> fallback.chat(systemPrompt, userPrompt),
                        MAX_RETRIES,
                        RETRY_BASE_DELAY,
                        e -> e instanceof LlmClient.LlmException le && le.isRetryable()
                );
                LOG.info("Review completed via {} (fallback)", fallback.getName());
                return result;
            } catch (Exception e) {
                this.lastError = fallback.getName() + " (fallback) failed: " + e.getMessage();
                LOG.error("{} (fallback) also failed: {}", fallback.getName(), e.getMessage());
            }
        }

        LOG.error("All LLM providers failed — this file will be skipped (last error: {})", lastError);
        return null;
    }

    /**
     * Returns the name of the client that would currently be used.
     */
    public String getActiveProviderName() {
        if (!primaryRateLimited && primary.isAvailable()) {
            return primary.getName();
        }
        if (fallback != null && fallback.isAvailable()) {
            return fallback.getName();
        }
        return "none";
    }
}
