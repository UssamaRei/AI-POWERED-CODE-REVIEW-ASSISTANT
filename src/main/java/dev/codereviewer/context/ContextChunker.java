package dev.codereviewer.context;

import dev.codereviewer.util.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ensures the combined prompt (diff + AST context + related context) fits
 * within the target LLM's token limit.
 *
 * <p>Chunking priority (truncate from lowest priority first):
 * <ol>
 *   <li>Diff hunks — never truncated (the core of the review)</li>
 *   <li>Changed method bodies — high priority</li>
 *   <li>Related API signatures — medium priority</li>
 *   <li>Other method signatures — lowest priority, truncated first</li>
 * </ol>
 *
 * <p>For Gemini Flash (~1M token context), truncation is rare. It matters
 * more for Groq's smaller context windows (~32k–128k tokens).
 */
public final class ContextChunker {

    private static final Logger LOG = LoggerFactory.getLogger(ContextChunker.class);

    /**
     * Default max tokens for the combined user prompt.
     * Conservative limit that works for both Gemini (1M) and Groq (128k).
     * The system prompt is separate and relatively small.
     */
    private static final int DEFAULT_MAX_PROMPT_TOKENS = 100_000;

    /**
     * Reserve tokens for the system prompt and response.
     */
    private static final int OVERHEAD_TOKENS = 10_000;

    private ContextChunker() {
        // utility class
    }

    /**
     * Truncates the related context to fit within token limits, preserving
     * the diff and changed method bodies at full length.
     *
     * @param diff            the raw diff (never truncated)
     * @param methodBodies    changed method bodies text
     * @param relatedContext  context from related files (truncated first)
     * @param maxTokens       maximum total tokens for the user prompt
     * @return the potentially truncated related context
     */
    public static String fitToLimit(String diff, String methodBodies,
                                     String relatedContext, int maxTokens) {
        if (maxTokens <= 0) {
            maxTokens = DEFAULT_MAX_PROMPT_TOKENS;
        }

        int available = maxTokens - OVERHEAD_TOKENS;

        int diffTokens = TokenEstimator.estimateTokens(diff);
        int methodTokens = TokenEstimator.estimateTokens(methodBodies);
        int relatedTokens = TokenEstimator.estimateTokens(relatedContext);

        int totalTokens = diffTokens + methodTokens + relatedTokens;

        if (totalTokens <= available) {
            return relatedContext; // Everything fits
        }

        LOG.info("Prompt too large ({} tokens), truncating related context (target: {} tokens)",
                totalTokens, available);

        // First: truncate related context
        int remainingForRelated = available - diffTokens - methodTokens;
        if (remainingForRelated > 0) {
            return TokenEstimator.truncateToFit(relatedContext, remainingForRelated);
        }

        // If even without related context it's too big, truncate method bodies too
        LOG.warn("Even without related context, prompt is {} tokens (limit: {}). " +
                "Method bodies will also be truncated.", diffTokens + methodTokens, available);

        return ""; // Drop related context entirely
    }

    /**
     * Convenience overload with default max tokens.
     */
    public static String fitToLimit(String diff, String methodBodies, String relatedContext) {
        return fitToLimit(diff, methodBodies, relatedContext, DEFAULT_MAX_PROMPT_TOKENS);
    }
}
