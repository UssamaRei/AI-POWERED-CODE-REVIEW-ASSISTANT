package dev.codereviewer.util;

/**
 * Rough token count estimator for LLM prompt sizing.
 *
 * <p>Uses a simple characters-per-token heuristic rather than pulling in a
 * full tokenizer library. For code (which tends to have shorter tokens than
 * natural language), ~3.5 chars/token is a reasonable estimate; we use 4 as
 * a conservative ceiling.
 *
 * <p>This is "good enough" for deciding whether to truncate context before
 * sending to the LLM. The LLM will handle exact tokenization internally.
 */
public final class TokenEstimator {

    /**
     * Average characters per token. Conservative estimate for code.
     * GPT/Gemini tokenizers typically yield 3.5–4.5 chars/token for code.
     */
    private static final double CHARS_PER_TOKEN = 4.0;

    private TokenEstimator() {
        // utility class
    }

    /**
     * Estimates the number of tokens in the given text.
     *
     * @param text the input text (may be null)
     * @return estimated token count, 0 if text is null or empty
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    /**
     * Returns true if the combined text would exceed the given token limit.
     *
     * @param texts    one or more text segments to sum
     * @param limit    maximum token count
     * @return true if estimated total tokens exceed the limit
     */
    public static boolean exceedsLimit(int limit, String... texts) {
        int total = 0;
        for (String text : texts) {
            total += estimateTokens(text);
            if (total > limit) {
                return true;
            }
        }
        return false;
    }

    /**
     * Truncates the text to approximately fit within the given token limit.
     * Truncation happens at the character level with an appended ellipsis marker.
     *
     * @param text     the input text
     * @param maxTokens maximum tokens to allow
     * @return the original text if it fits, or a truncated version
     */
    public static String truncateToFit(String text, int maxTokens) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        int estimatedTokens = estimateTokens(text);
        if (estimatedTokens <= maxTokens) {
            return text;
        }

        int maxChars = (int) (maxTokens * CHARS_PER_TOKEN);
        String truncationMarker = "\n\n... [truncated, " + estimatedTokens + " tokens exceeded limit of " + maxTokens + "] ...";

        // Leave room for the truncation marker
        int cutPoint = Math.max(0, maxChars - truncationMarker.length());

        // Try to cut at a newline boundary for cleaner truncation
        int lastNewline = text.lastIndexOf('\n', cutPoint);
        if (lastNewline > cutPoint * 0.8) { // only use newline if it's not too far back
            cutPoint = lastNewline;
        }

        return text.substring(0, cutPoint) + truncationMarker;
    }
}
