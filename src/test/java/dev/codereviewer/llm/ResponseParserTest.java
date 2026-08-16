package dev.codereviewer.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import dev.codereviewer.config.ReviewConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ResponseParser}.
 */
class ResponseParserTest {

    @Test
    @DisplayName("Parses wrapped JSON format: {findings: [...]}")
    void parseWrappedFormat() {
        String json = """
                {
                  "findings": [
                    {
                      "file": "Service.java",
                      "line": 42,
                      "severity": "WARNING",
                      "category": "null-safety",
                      "comment": "This could throw NPE if repo returns null."
                    },
                    {
                      "file": "Service.java",
                      "line": 55,
                      "severity": "SUGGESTION",
                      "category": "naming",
                      "comment": "Consider a more descriptive method name."
                    }
                  ]
                }
                """;

        List<ReviewFinding> findings = ResponseParser.parse(json, "Service.java");

        assertEquals(2, findings.size());
        assertEquals(42, findings.get(0).line());
        assertEquals(ReviewConfig.Severity.WARNING, findings.get(0).severity());
        assertEquals("null-safety", findings.get(0).category());
    }

    @Test
    @DisplayName("Parses raw array format: [...]")
    void parseArrayFormat() {
        String json = """
                [
                  {
                    "file": "App.java",
                    "line": 10,
                    "severity": "CRITICAL",
                    "category": "resource-leak",
                    "comment": "Stream is never closed."
                  }
                ]
                """;

        List<ReviewFinding> findings = ResponseParser.parse(json, "App.java");

        assertEquals(1, findings.size());
        assertEquals(ReviewConfig.Severity.CRITICAL, findings.get(0).severity());
    }

    @Test
    @DisplayName("Handles markdown-wrapped JSON (```json ... ```)")
    void parseMarkdownWrapped() {
        String json = """
                ```json
                {
                  "findings": [
                    {
                      "file": "Test.java",
                      "line": 5,
                      "severity": "NITPICK",
                      "category": "naming",
                      "comment": "Variable name 'x' is not descriptive."
                    }
                  ]
                }
                ```
                """;

        List<ReviewFinding> findings = ResponseParser.parse(json, "Test.java");

        assertEquals(1, findings.size());
        assertEquals(ReviewConfig.Severity.NITPICK, findings.get(0).severity());
    }

    @Test
    @DisplayName("Returns empty list for empty findings array")
    void parseEmptyFindings() {
        String json = """
                {"findings": []}
                """;

        List<ReviewFinding> findings = ResponseParser.parse(json, "Clean.java");
        assertTrue(findings.isEmpty());
    }

    @Test
    @DisplayName("Drops findings with invalid line numbers")
    void dropsInvalidLines() {
        String json = """
                {
                  "findings": [
                    {"file": "A.java", "line": -1, "severity": "WARNING", "category": "bug", "comment": "bad line"},
                    {"file": "A.java", "line": 0, "severity": "WARNING", "category": "bug", "comment": "zero line"},
                    {"file": "A.java", "line": 10, "severity": "WARNING", "category": "bug", "comment": "valid"}
                  ]
                }
                """;

        List<ReviewFinding> findings = ResponseParser.parse(json, "A.java");
        assertEquals(1, findings.size());
        assertEquals(10, findings.get(0).line());
    }

    @Test
    @DisplayName("Handles unknown severity gracefully")
    void unknownSeverity() {
        String json = """
                {
                  "findings": [
                    {"file": "X.java", "line": 1, "severity": "MEDIUM", "category": "bug", "comment": "test"}
                  ]
                }
                """;

        List<ReviewFinding> findings = ResponseParser.parse(json, "X.java");
        assertEquals(1, findings.size());
        assertEquals(ReviewConfig.Severity.SUGGESTION, findings.get(0).severity()); // default
    }

    @Test
    @DisplayName("Returns empty list for completely malformed JSON")
    void malformedJson() {
        List<ReviewFinding> findings = ResponseParser.parse("not json at all", "X.java");
        assertTrue(findings.isEmpty());
    }

    @Test
    @DisplayName("Returns empty list for null input")
    void nullInput() {
        assertTrue(ResponseParser.parse(null, "X.java").isEmpty());
        assertTrue(ResponseParser.parse("", "X.java").isEmpty());
    }

    @Test
    @DisplayName("Fills in missing file field from expectedFile parameter")
    void fillsMissingFile() {
        String json = """
                {
                  "findings": [
                    {"line": 5, "severity": "WARNING", "category": "bug", "comment": "test finding"}
                  ]
                }
                """;

        List<ReviewFinding> findings = ResponseParser.parse(json, "Expected.java");
        assertEquals(1, findings.size());
        assertEquals("Expected.java", findings.get(0).file());
    }
}
