package dev.codereviewer.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TokenEstimator}.
 */
class TokenEstimatorTest {

    @Test
    @DisplayName("Estimates tokens from character count")
    void estimateTokens() {
        assertEquals(0, TokenEstimator.estimateTokens(null));
        assertEquals(0, TokenEstimator.estimateTokens(""));
        assertEquals(3, TokenEstimator.estimateTokens("hello world!")); // 12 chars / 4 = 3
    }

    @Test
    @DisplayName("exceedsLimit detects oversize prompts")
    void exceedsLimit() {
        // 100 chars = ~25 tokens
        String text = "x".repeat(100);
        assertFalse(TokenEstimator.exceedsLimit(30, text));
        assertTrue(TokenEstimator.exceedsLimit(20, text));
    }

    @Test
    @DisplayName("truncateToFit preserves short text")
    void truncatePreservesShort() {
        String text = "short text";
        assertEquals(text, TokenEstimator.truncateToFit(text, 100));
    }

    @Test
    @DisplayName("truncateToFit truncates long text")
    void truncateLongText() {
        String text = "a".repeat(1000);
        String result = TokenEstimator.truncateToFit(text, 50);
        assertTrue(result.length() < text.length());
        assertTrue(result.contains("[truncated"));
    }

    @Test
    @DisplayName("truncateToFit handles null/empty")
    void truncateNullEmpty() {
        assertNull(TokenEstimator.truncateToFit(null, 100));
        assertEquals("", TokenEstimator.truncateToFit("", 100));
    }
}
