package dev.codereviewer.llm;

import java.util.List;

/**
 * Interface for LLM providers (Gemini, Groq, etc.).
 *
 * <p>Each implementation wraps a single LLM's REST API and handles:
 * <ul>
 *   <li>Authentication (API key)</li>
 *   <li>Request formatting (provider-specific JSON structure)</li>
 *   <li>Response parsing (extract text from provider-specific response format)</li>
 *   <li>Error detection (rate limits, server errors)</li>
 * </ul>
 */
public interface LlmClient {

    /**
     * Returns the human-readable name of this provider (e.g., "Gemini", "Groq").
     */
    String getName();

    /**
     * Sends a chat request to the LLM and returns the raw response text.
     *
     * @param systemPrompt the system/instruction prompt
     * @param userPrompt   the user prompt with the actual review request
     * @return the LLM's response text (expected to be JSON)
     * @throws LlmException if the call fails (with a retryable flag)
     */
    String chat(String systemPrompt, String userPrompt) throws LlmException;

    /**
     * Returns true if this client is configured and likely to succeed.
     * (e.g., has a valid API key set)
     */
    boolean isAvailable();

    /**
     * Custom exception for LLM call failures.
     */
    class LlmException extends Exception {
        private final boolean retryable;
        private final int httpStatus;

        public LlmException(String message, boolean retryable, int httpStatus) {
            super(message);
            this.retryable = retryable;
            this.httpStatus = httpStatus;
        }

        public LlmException(String message, Throwable cause, boolean retryable) {
            super(message, cause);
            this.retryable = retryable;
            this.httpStatus = -1;
        }

        /**
         * Returns true if the error is transient and the request should be retried
         * (e.g., 429 rate limit, 500 server error).
         */
        public boolean isRetryable() {
            return retryable;
        }

        public int getHttpStatus() {
            return httpStatus;
        }
    }
}
