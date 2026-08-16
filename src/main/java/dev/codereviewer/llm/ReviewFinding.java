package dev.codereviewer.llm;

import dev.codereviewer.config.ReviewConfig;

/**
 * Represents a single finding from the LLM code review.
 *
 * <p>This is the structured output format that the LLM is instructed to return
 * as JSON. Each finding maps to one inline comment on the PR.
 */
public record ReviewFinding(
        /** File path relative to repo root */
        String file,

        /** Line number in the new file (post-change) */
        int line,

        /** Severity of the finding */
        ReviewConfig.Severity severity,

        /** Category tag (e.g., "null-safety", "concurrency", "naming") */
        String category,

        /** The review comment text — concise and actionable */
        String comment
) {
    /**
     * Validates this finding and returns true if it's well-formed enough to post.
     */
    public boolean isValid() {
        return file != null && !file.isBlank()
                && line > 0
                && severity != null
                && comment != null && !comment.isBlank();
    }
}
