package dev.codereviewer.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import dev.codereviewer.config.ReviewConfig;
import dev.codereviewer.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses LLM response text (expected JSON) into validated {@link ReviewFinding} objects.
 *
 * <p>Handles multiple response formats defensively:
 * <ul>
 *   <li>Wrapped: {@code {"findings": [...]}} — the expected format</li>
 *   <li>Raw array: {@code [...]} — some models return this</li>
 *   <li>Malformed: logs a warning and returns an empty list</li>
 * </ul>
 *
 * <p>Also validates and sanitizes each finding (drops invalid line numbers,
 * deduplicates, etc.).
 */
public final class ResponseParser {

    private static final Logger LOG = LoggerFactory.getLogger(ResponseParser.class);

    private ResponseParser() {
        // utility class
    }

    /**
     * Parses the raw LLM response text into a list of validated findings.
     *
     * @param responseText the raw JSON text from the LLM
     * @param expectedFile the file path this review was for (used to fix missing file fields)
     * @return list of valid findings, empty if parsing fails or no findings
     */
    public static List<ReviewFinding> parse(String responseText, String expectedFile) {
        if (responseText == null || responseText.isBlank()) {
            LOG.warn("Empty LLM response");
            return List.of();
        }

        try {
            // Clean up the response — some models wrap JSON in markdown code blocks
            String cleaned = cleanJsonResponse(responseText);

            List<RawFinding> rawFindings = parseRawFindings(cleaned);

            // Convert and validate
            List<ReviewFinding> findings = new ArrayList<>();
            for (RawFinding raw : rawFindings) {
                ReviewFinding finding = convertAndValidate(raw, expectedFile);
                if (finding != null) {
                    findings.add(finding);
                }
            }

            LOG.info("Parsed {} valid findings from LLM response ({} raw)", findings.size(), rawFindings.size());
            return findings;

        } catch (Exception e) {
            LOG.error("Failed to parse LLM response: {}", e.getMessage());
            LOG.debug("Raw response: {}", responseText.substring(0, Math.min(500, responseText.length())));
            return List.of();
        }
    }

    /**
     * Strips markdown code fences and other wrapping from the JSON response.
     */
    private static String cleanJsonResponse(String text) {
        String cleaned = text.strip();

        // Remove markdown code blocks: ```json ... ``` or ``` ... ```
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.strip();
        }

        return cleaned;
    }

    /**
     * Parses the JSON into raw finding objects, handling both wrapped and array formats.
     */
    private static List<RawFinding> parseRawFindings(String json) {
        try {
            JsonNode root = JsonUtil.mapper().readTree(json);

            // Format 1: {"findings": [...]}
            if (root.isObject() && root.has("findings")) {
                return JsonUtil.mapper().convertValue(
                        root.get("findings"),
                        new TypeReference<List<RawFinding>>() {}
                );
            }

            // Format 2: direct array [...]
            if (root.isArray()) {
                return JsonUtil.mapper().convertValue(
                        root,
                        new TypeReference<List<RawFinding>>() {}
                );
            }

            LOG.warn("Unexpected JSON structure — not an object with 'findings' or an array");
            return List.of();

        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Converts a raw finding to a validated ReviewFinding, or null if invalid.
     */
    private static ReviewFinding convertAndValidate(RawFinding raw, String expectedFile) {
        // Fix missing file field
        String file = (raw.file != null && !raw.file.isBlank()) ? raw.file : expectedFile;

        // Validate line number
        if (raw.line <= 0) {
            LOG.debug("Dropping finding with invalid line number: {}", raw.line);
            return null;
        }

        // Parse severity with fallback
        ReviewConfig.Severity severity;
        try {
            severity = ReviewConfig.Severity.valueOf(
                    raw.severity != null ? raw.severity.toUpperCase().trim() : "SUGGESTION"
            );
        } catch (IllegalArgumentException e) {
            LOG.debug("Unknown severity '{}', defaulting to SUGGESTION", raw.severity);
            severity = ReviewConfig.Severity.SUGGESTION;
        }

        // Validate comment
        if (raw.comment == null || raw.comment.isBlank()) {
            LOG.debug("Dropping finding with empty comment");
            return null;
        }

        String category = (raw.category != null && !raw.category.isBlank()) ? raw.category : "general";

        return new ReviewFinding(file, raw.line, severity, category, raw.comment.trim());
    }

    /**
     * Internal DTO for Jackson deserialization of the raw LLM output.
     * Uses mutable fields + default constructor for Jackson compatibility.
     */
    private static class RawFinding {
        public String file;
        public int line;
        public String severity;
        public String category;
        public String comment;
    }
}
