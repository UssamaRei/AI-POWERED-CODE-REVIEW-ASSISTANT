package dev.codereviewer.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codereviewer.config.ReviewConfig;
import dev.codereviewer.config.ReviewConfig.Severity;
import dev.codereviewer.llm.ReviewFinding;
import dev.codereviewer.review.FileReviewTask;
import dev.codereviewer.util.LanguageDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Calculates code health scores and exports review metrics to JSON files
 * for dashboard visualization and trend analytics.
 */
public class ReviewMetricsExporter {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewMetricsExporter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Calculates a 0-100 Code Health Score based on finding counts and severities.
     */
    public static int calculateHealthScore(List<ReviewFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return 100;
        }

        int penalty = 0;
        for (ReviewFinding f : findings) {
            penalty += switch (f.severity()) {
                case CRITICAL -> 15;
                case WARNING -> 5;
                case SUGGESTION -> 1;
                case NITPICK -> 0;
            };
        }

        return Math.max(0, 100 - penalty);
    }

    /**
     * Returns a letter grade corresponding to the quality score.
     */
    public static String getGrade(int score) {
        if (score >= 95) return "A+";
        if (score >= 90) return "A";
        if (score >= 80) return "B";
        if (score >= 70) return "C";
        if (score >= 60) return "D";
        return "F";
    }

    /**
     * Exports review metrics for the current PR to `.reviews/` folder.
     */
    public static void export(ReviewConfig config, List<FileReviewTask.Result> results) {
        try {
            Path outputDir = Path.of(config.getWorkspacePath(), ".reviews");
            Files.createDirectories(outputDir);

            List<ReviewFinding> allFindings = results.stream()
                    .filter(FileReviewTask.Result::success)
                    .flatMap(r -> r.findings().stream())
                    .toList();

            int score = calculateHealthScore(allFindings);
            String grade = getGrade(score);

            Map<Severity, Long> severityCounts = new EnumMap<>(Severity.class);
            for (Severity s : Severity.values()) {
                severityCounts.put(s, allFindings.stream().filter(f -> f.severity() == s).count());
            }

            Map<String, Integer> categoryCounts = new HashMap<>();
            Set<String> languages = new TreeSet<>();
            for (ReviewFinding f : allFindings) {
                categoryCounts.merge(f.category(), 1, Integer::sum);
            }

            for (FileReviewTask.Result r : results) {
                languages.add(LanguageDetector.detectLanguage(r.filename()));
            }

            // Build individual PR JSON record
            ObjectNode prNode = MAPPER.createObjectNode();
            prNode.put("prNumber", config.getPullRequestNumber());
            prNode.put("repository", config.getRepository());
            prNode.put("timestamp", Instant.now().toString());
            prNode.put("headSha", config.getHeadSha());
            prNode.put("qualityScore", score);
            prNode.put("grade", grade);
            prNode.put("filesReviewed", results.stream().filter(FileReviewTask.Result::success).count());
            prNode.put("filesSkipped", results.stream().filter(r -> !r.success()).count());

            ArrayNode langArray = prNode.putArray("languages");
            languages.forEach(langArray::add);

            ObjectNode sevNode = prNode.putObject("severities");
            sevNode.put("critical", severityCounts.getOrDefault(Severity.CRITICAL, 0L));
            sevNode.put("warning", severityCounts.getOrDefault(Severity.WARNING, 0L));
            sevNode.put("suggestion", severityCounts.getOrDefault(Severity.SUGGESTION, 0L));
            sevNode.put("nitpick", severityCounts.getOrDefault(Severity.NITPICK, 0L));

            ObjectNode catNode = prNode.putObject("categories");
            categoryCounts.forEach(catNode::put);

            ArrayNode filesArray = prNode.putArray("files");
            for (FileReviewTask.Result r : results) {
                ObjectNode fileNode = filesArray.addObject();
                fileNode.put("path", r.filename());
                fileNode.put("language", LanguageDetector.detectLanguage(r.filename()));
                fileNode.put("status", r.success() ? "reviewed" : "skipped");
                if (r.success()) {
                    fileNode.put("findingsCount", r.findings().size());
                } else {
                    fileNode.put("skipReason", r.skipReason());
                }
            }

            // Write individual PR metrics file
            Path prFile = outputDir.resolve("pr-" + config.getPullRequestNumber() + ".json");
            MAPPER.writeValue(prFile.toFile(), prNode);
            LOG.info("✓ Exported PR review metrics to {}", prFile);

            // Update cumulative summary.json
            updateSummary(outputDir, prNode);

        } catch (Exception e) {
            LOG.warn("Failed to export review metrics: {}", e.getMessage());
        }
    }

    private static void updateSummary(Path outputDir, ObjectNode newPrNode) {
        Path summaryFile = outputDir.resolve("summary.json");
        try {
            ObjectNode summaryNode;
            ArrayNode historyArray;

            if (Files.exists(summaryFile)) {
                JsonNode existing = MAPPER.readTree(summaryFile.toFile());
                summaryNode = existing.isObject() ? (ObjectNode) existing : MAPPER.createObjectNode();
                historyArray = summaryNode.has("history") && summaryNode.get("history").isArray()
                        ? (ArrayNode) summaryNode.get("history")
                        : summaryNode.putArray("history");
            } else {
                summaryNode = MAPPER.createObjectNode();
                historyArray = summaryNode.putArray("history");
            }

            // Replace existing PR entry if already reviewed or append
            int prNum = newPrNode.get("prNumber").asInt();
            int replaceIndex = -1;
            for (int i = 0; i < historyArray.size(); i++) {
                if (historyArray.get(i).has("prNumber") && historyArray.get(i).get("prNumber").asInt() == prNum) {
                    replaceIndex = i;
                    break;
                }
            }

            if (replaceIndex >= 0) {
                historyArray.set(replaceIndex, newPrNode);
            } else {
                historyArray.insert(0, newPrNode); // most recent first
            }

            // Recalculate global cumulative aggregates
            int totalPrs = historyArray.size();
            long totalCritical = 0;
            long totalWarning = 0;
            long totalSuggestion = 0;
            long totalFiles = 0;
            int totalScore = 0;
            Map<String, Integer> globalCategories = new HashMap<>();

            for (JsonNode item : historyArray) {
                totalScore += item.path("qualityScore").asInt(100);
                totalFiles += item.path("filesReviewed").asLong(0);
                totalCritical += item.path("severities").path("critical").asLong(0);
                totalWarning += item.path("severities").path("warning").asLong(0);
                totalSuggestion += item.path("severities").path("suggestion").asLong(0);

                JsonNode cats = item.path("categories");
                if (cats.isObject()) {
                    cats.fields().forEachRemaining(entry ->
                            globalCategories.merge(entry.getKey(), entry.getValue().asInt(), Integer::sum));
                }
            }

            int avgScore = totalPrs > 0 ? totalScore / totalPrs : 100;
            summaryNode.put("lastUpdated", Instant.now().toString());
            summaryNode.put("totalPrsReviewed", totalPrs);
            summaryNode.put("averageQualityScore", avgScore);
            summaryNode.put("averageGrade", getGrade(avgScore));
            summaryNode.put("totalFilesReviewed", totalFiles);
            summaryNode.put("totalBugsPrevented", totalCritical + totalWarning);

            ObjectNode globalSev = summaryNode.putObject("totalSeverities");
            globalSev.put("critical", totalCritical);
            globalSev.put("warning", totalWarning);
            globalSev.put("suggestion", totalSuggestion);

            ObjectNode globalCatNode = summaryNode.putObject("totalCategories");
            globalCategories.forEach(globalCatNode::put);

            MAPPER.writeValue(summaryFile.toFile(), summaryNode);
            LOG.info("✓ Updated cumulative review metrics index at {}", summaryFile);

        } catch (Exception e) {
            LOG.warn("Failed to update summary.json: {}", e.getMessage());
        }
    }
}
