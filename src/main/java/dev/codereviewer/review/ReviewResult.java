package dev.codereviewer.review;

import dev.codereviewer.config.ReviewConfig;
import dev.codereviewer.llm.ReviewFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregated results from reviewing all files in a PR.
 *
 * <p>Collects findings from individual file reviews, computes severity
 * statistics, tracks skipped files, and generates the summary comment
 * that appears at the top of the PR review.
 */
public class ReviewResult {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewResult.class);

    private final List<ReviewFinding> findings = new ArrayList<>();
    private final List<String> skippedFiles = new ArrayList<>();
    private final List<String> reviewedFiles = new ArrayList<>();
    private final List<FileReviewTask.Result> fileResults = new ArrayList<>();

    /**
     * Adds findings from a single file's review.
     */
    public synchronized void addFindings(String filename, List<ReviewFinding> fileFindings) {
        reviewedFiles.add(filename);
        findings.addAll(fileFindings);
    }

    /**
     * Adds raw file result.
     */
    public synchronized void addFileResult(FileReviewTask.Result res) {
        fileResults.add(res);
        if (res.success()) {
            reviewedFiles.add(res.filename());
            findings.addAll(res.findings());
        } else {
            skippedFiles.add(res.filename() + " (" + res.skipReason() + ")");
        }
    }

    public List<FileReviewTask.Result> getFileResults() {
        return Collections.unmodifiableList(fileResults);
    }

    /**
     * Records a file that was skipped (parse failure, LLM timeout, etc.).
     */
    public synchronized void addSkippedFile(String filename, String reason) {
        skippedFiles.add(filename + " (" + reason + ")");
    }

    public List<ReviewFinding> getFindings() {
        return Collections.unmodifiableList(findings);
    }

    public List<String> getSkippedFiles() {
        return Collections.unmodifiableList(skippedFiles);
    }

    /**
     * Returns the count of findings by severity.
     */
    public Map<ReviewConfig.Severity, Long> getSeverityCounts() {
        return findings.stream()
                .collect(Collectors.groupingBy(
                        ReviewFinding::severity,
                        Collectors.counting()
                ));
    }

    /**
     * Generates a markdown summary for the PR review comment.
     */
    public String generateSummary() {
        int score = dev.codereviewer.metrics.ReviewMetricsExporter.calculateHealthScore(findings);
        String grade = dev.codereviewer.metrics.ReviewMetricsExporter.getGrade(score);

        StringBuilder sb = new StringBuilder();
        sb.append("## 🤖 AI Code Review Summary\n\n");
        sb.append("### 📊 Code Health Score: **").append(score).append(" / 100** (`Grade: ").append(grade).append("`)\n\n");

        if (findings.isEmpty()) {
            sb.append("✅ **No issues found.** The code changes look clean and well-structured!\n\n");
        } else {
            Map<ReviewConfig.Severity, Long> counts = getSeverityCounts();

            sb.append("Found **").append(findings.size()).append(" issue(s)** across **")
                    .append(reviewedFiles.size()).append(" file(s)**:\n\n");

            // Severity breakdown with emojis
            if (counts.containsKey(ReviewConfig.Severity.CRITICAL)) {
                sb.append("- 🔴 **").append(counts.get(ReviewConfig.Severity.CRITICAL)).append("** critical\n");
            }
            if (counts.containsKey(ReviewConfig.Severity.WARNING)) {
                sb.append("- 🟡 **").append(counts.get(ReviewConfig.Severity.WARNING)).append("** warning(s)\n");
            }
            if (counts.containsKey(ReviewConfig.Severity.SUGGESTION)) {
                sb.append("- 🔵 **").append(counts.get(ReviewConfig.Severity.SUGGESTION)).append("** suggestion(s)\n");
            }
            if (counts.containsKey(ReviewConfig.Severity.NITPICK)) {
                sb.append("- ⚪ **").append(counts.get(ReviewConfig.Severity.NITPICK)).append("** nitpick(s)\n");
            }

            sb.append("\n");

            // Category breakdown
            Map<String, Long> categories = findings.stream()
                    .collect(Collectors.groupingBy(ReviewFinding::category, Collectors.counting()));

            sb.append("**Most common categories:** ");
            sb.append(categories.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(3)
                    .map(e -> "`" + e.getKey() + "` (" + e.getValue() + ")")
                    .collect(Collectors.joining(", ")));
            sb.append("\n\n");
        }

        // Reviewed files
        sb.append("<details>\n<summary>📁 Files reviewed (").append(reviewedFiles.size()).append(")</summary>\n\n");
        for (String file : reviewedFiles) {
            sb.append("- `").append(file).append("`\n");
        }
        sb.append("\n</details>\n\n");

        // Skipped files
        if (!skippedFiles.isEmpty()) {
            sb.append("<details>\n<summary>⏭️ Files skipped (").append(skippedFiles.size()).append(")</summary>\n\n");
            for (String file : skippedFiles) {
                sb.append("- ").append(file).append("\n");
            }
            sb.append("\n</details>\n\n");
        }

        sb.append("---\n");
        sb.append("*Powered by [AI Code Review Assistant](https://github.com/your-username/ai-code-review-assistant) — ");
        sb.append("automated review, not a substitute for human review.*");

        return sb.toString();
    }

    /**
     * Returns true if there are any critical or warning findings.
     */
    public boolean hasBlockingFindings() {
        return findings.stream().anyMatch(f ->
                f.severity() == ReviewConfig.Severity.CRITICAL
                || f.severity() == ReviewConfig.Severity.WARNING
        );
    }
}
