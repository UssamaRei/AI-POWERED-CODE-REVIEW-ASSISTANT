package dev.codereviewer.metrics;

import dev.codereviewer.config.ReviewConfig;
import dev.codereviewer.config.ReviewConfig.Severity;
import dev.codereviewer.llm.ReviewFinding;
import dev.codereviewer.review.FileReviewTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ReviewMetricsExporter Tests")
class ReviewMetricsExporterTest {

    @Test
    @DisplayName("Health score calculation correctly weights critical, warning, and suggestion issues")
    void testCalculateHealthScore() {
        // No findings -> 100%
        assertEquals(100, ReviewMetricsExporter.calculateHealthScore(List.of()));

        // 1 Critical (15) + 1 Warning (5) + 1 Suggestion (1) = 21 penalty -> 79%
        List<ReviewFinding> findings = List.of(
                new ReviewFinding("File.java", 10, Severity.CRITICAL, "security", "SQL injection"),
                new ReviewFinding("File.java", 20, Severity.WARNING, "resource-leak", "Stream unclosed"),
                new ReviewFinding("File.java", 30, Severity.SUGGESTION, "naming", "Rename var")
        );
        assertEquals(79, ReviewMetricsExporter.calculateHealthScore(findings));

        // Score cannot go below 0
        List<ReviewFinding> manyCriticals = List.of(
                new ReviewFinding("A.java", 1, Severity.CRITICAL, "sec", "bug"),
                new ReviewFinding("A.java", 2, Severity.CRITICAL, "sec", "bug"),
                new ReviewFinding("A.java", 3, Severity.CRITICAL, "sec", "bug"),
                new ReviewFinding("A.java", 4, Severity.CRITICAL, "sec", "bug"),
                new ReviewFinding("A.java", 5, Severity.CRITICAL, "sec", "bug"),
                new ReviewFinding("A.java", 6, Severity.CRITICAL, "sec", "bug"),
                new ReviewFinding("A.java", 7, Severity.CRITICAL, "sec", "bug"),
                new ReviewFinding("A.java", 8, Severity.CRITICAL, "sec", "bug")
        );
        assertEquals(0, ReviewMetricsExporter.calculateHealthScore(manyCriticals));
    }

    @Test
    @DisplayName("Grade ranges return correct letter grade")
    void testGetGrade() {
        assertEquals("A+", ReviewMetricsExporter.getGrade(98));
        assertEquals("A", ReviewMetricsExporter.getGrade(92));
        assertEquals("B", ReviewMetricsExporter.getGrade(85));
        assertEquals("C", ReviewMetricsExporter.getGrade(72));
        assertEquals("D", ReviewMetricsExporter.getGrade(64));
        assertEquals("F", ReviewMetricsExporter.getGrade(40));
    }

    @Test
    @DisplayName("Export creates pr JSON and cumulative summary.json in .reviews directory")
    void testExport(@TempDir Path tempDir) {
        ReviewConfig config = new ReviewConfig.Builder()
                .githubToken("fake-token")
                .repository("owner/repo")
                .pullRequestNumber(1)
                .headSha("abc1234")
                .baseRef("main")
                .geminiApiKey("fake-key")
                .workspacePath(tempDir.toString())
                .build();

        List<FileReviewTask.Result> results = List.of(
                FileReviewTask.Result.success("src/UserManager.java", List.of(
                        new ReviewFinding("src/UserManager.java", 15, Severity.CRITICAL, "security", "SQL Injection"),
                        new ReviewFinding("src/UserManager.java", 25, Severity.WARNING, "resource-leak", "Unclosed statement")
                )),
                FileReviewTask.Result.success("scripts/deploy.py", List.of(
                        new ReviewFinding("scripts/deploy.py", 8, Severity.WARNING, "error-handling", "Bare except")
                ))
        );

        ReviewMetricsExporter.export(config, results);

        Path reviewsDir = tempDir.resolve(".reviews");
        assertTrue(Files.exists(reviewsDir), ".reviews dir should be created");
        assertTrue(Files.exists(reviewsDir.resolve("pr-1.json")), "pr-1.json should be created");
        assertTrue(Files.exists(reviewsDir.resolve("summary.json")), "summary.json should be created");
    }
}
