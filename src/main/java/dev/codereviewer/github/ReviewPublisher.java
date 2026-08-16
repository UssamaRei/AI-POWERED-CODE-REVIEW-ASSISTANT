package dev.codereviewer.github;

import dev.codereviewer.config.ReviewConfig;
import dev.codereviewer.llm.ReviewFinding;
import dev.codereviewer.review.ReviewResult;
import org.kohsuke.github.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/**
 * Publishes review findings back to GitHub as PR review comments.
 *
 * <p>Posts both inline comments on specific diff lines and a summary comment
 * at the top of the review. Handles fork PRs gracefully by falling back to
 * the GitHub Actions Job Summary when write permissions are unavailable.
 */
public class ReviewPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewPublisher.class);

    private final GHRepository repository;

    public ReviewPublisher(GHRepository repository) {
        this.repository = repository;
    }

    /**
     * Publishes a complete review on the pull request.
     *
     * @param prNumber    the PR number
     * @param headSha     the head commit SHA (required by GitHub review API)
     * @param result      aggregated review results
     * @param threshold   minimum severity to include
     */
    public void publishReview(int prNumber, String headSha, ReviewResult result,
                              ReviewConfig.Severity threshold) {
        List<ReviewFinding> findings = result.getFindings().stream()
                .filter(f -> f.severity().meetsThreshold(threshold))
                .toList();

        String summary = result.generateSummary();

        try {
            postGitHubReview(prNumber, headSha, findings, summary);
        } catch (IOException e) {
            LOG.warn("Failed to post GitHub review (possibly fork PR with read-only token): {}",
                    e.getMessage());
            LOG.info("Falling back to GitHub Actions Job Summary");
            writeToJobSummary(summary, findings);
        }
    }

    /**
     * Posts a PR review with inline comments via the GitHub API.
     */
    private void postGitHubReview(int prNumber, String headSha,
                                  List<ReviewFinding> findings, String summary)
            throws IOException {

        GHPullRequest pr = repository.getPullRequest(prNumber);

        // Determine the review event based on severity of findings
        GHPullRequestReviewEvent event = determineReviewEvent(findings);

        // Build the review
        GHPullRequestReviewBuilder reviewBuilder = pr.createReview()
                .commitId(headSha)
                .body(summary)
                .event(event);

        // Add inline comments for findings that are on valid diff lines
        int inlineCount = 0;
        int skippedCount = 0;

        for (ReviewFinding finding : findings) {
            if (finding.line() > 0 && finding.file() != null) {
                try {
                    String commentBody = formatInlineComment(finding);
                    reviewBuilder.comment(commentBody, finding.file(), finding.line());
                    inlineCount++;
                } catch (Exception e) {
                    // GitHub API rejects comments on lines not in the diff
                    LOG.debug("Could not attach comment to {}:{} — likely not in diff",
                            finding.file(), finding.line());
                    skippedCount++;
                }
            }
        }

        reviewBuilder.create();

        LOG.info("Posted review on PR #{}: {} inline comments ({} skipped), event={}",
                prNumber, inlineCount, skippedCount, event);
    }

    /**
     * Determines whether to post as COMMENT or REQUEST_CHANGES based on findings.
     */
    private GHPullRequestReviewEvent determineReviewEvent(List<ReviewFinding> findings) {
        boolean hasCritical = findings.stream()
                .anyMatch(f -> f.severity() == ReviewConfig.Severity.CRITICAL
                        || f.severity() == ReviewConfig.Severity.WARNING);

        return hasCritical
                ? GHPullRequestReviewEvent.REQUEST_CHANGES
                : GHPullRequestReviewEvent.COMMENT;
    }

    /**
     * Formats a finding as a markdown inline comment.
     */
    private String formatInlineComment(ReviewFinding finding) {
        String severityEmoji = switch (finding.severity()) {
            case CRITICAL  -> "🔴";
            case WARNING   -> "🟡";
            case SUGGESTION -> "🔵";
            case NITPICK   -> "⚪";
        };

        return String.format("""
                %s **%s** | `%s`
                
                %s""",
                severityEmoji,
                finding.severity(),
                finding.category(),
                finding.comment());
    }

    /**
     * Fallback: writes findings to the GitHub Actions Job Summary.
     * This is used when the GITHUB_TOKEN doesn't have write access (e.g., fork PRs).
     */
    private void writeToJobSummary(String summary, List<ReviewFinding> findings) {
        String summaryPath = System.getenv("GITHUB_STEP_SUMMARY");
        if (summaryPath == null) {
            LOG.warn("GITHUB_STEP_SUMMARY not set — cannot write job summary fallback");
            LOG.info("Review summary:\n{}", summary);
            return;
        }

        StringBuilder md = new StringBuilder();
        md.append("## 🤖 AI Code Review Results\n\n");
        md.append(summary).append("\n\n");

        if (!findings.isEmpty()) {
            md.append("### Findings\n\n");
            md.append("| Severity | File | Line | Category | Comment |\n");
            md.append("|----------|------|------|----------|---------|\n");

            for (ReviewFinding f : findings) {
                md.append(String.format("| %s | `%s` | %d | `%s` | %s |\n",
                        f.severity(), f.file(), f.line(), f.category(),
                        f.comment().replace("|", "\\|").replace("\n", " ")));
            }
        }

        try {
            Files.writeString(Path.of(summaryPath), md.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            LOG.info("Wrote review to GitHub Actions Job Summary");
        } catch (IOException e) {
            LOG.error("Failed to write to GITHUB_STEP_SUMMARY: {}", e.getMessage());
            // Last resort: dump to stdout
            System.out.println(md);
        }
    }
}
