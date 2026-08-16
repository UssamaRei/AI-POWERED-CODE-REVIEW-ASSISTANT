package dev.codereviewer.review;

import dev.codereviewer.config.ReviewConfig;
import dev.codereviewer.context.ContextBuilder;
import dev.codereviewer.github.PullRequestService;
import dev.codereviewer.github.ReviewPublisher;
import dev.codereviewer.llm.LlmRouter;
import dev.codereviewer.parser.AstAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * End-to-end review orchestrator — the main pipeline entry point.
 *
 * <p>Coordinates all components to review a pull request:
 * <ol>
 *   <li>Fetch changed files from GitHub</li>
 *   <li>Review each file concurrently using virtual threads</li>
 *   <li>Aggregate findings</li>
 *   <li>Post the review back to GitHub</li>
 * </ol>
 *
 * <p><b>Virtual threads (Java 21):</b> Each file review runs in its own virtual
 * thread via {@link Executors#newVirtualThreadPerTaskExecutor()}. This gives
 * natural concurrency for LLM I/O without configuring thread pool sizes.
 * Virtual threads are lightweight enough that even 15 concurrent file reviews
 * won't strain memory — they're blocked on HTTP I/O, not CPU.
 */
public class ReviewOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewOrchestrator.class);

    private final ReviewConfig config;
    private final PullRequestService prService;
    private final ReviewPublisher publisher;
    private final AstAnalyzer astAnalyzer;
    private final LlmRouter llmRouter;

    public ReviewOrchestrator(ReviewConfig config,
                              PullRequestService prService,
                              ReviewPublisher publisher,
                              AstAnalyzer astAnalyzer,
                              LlmRouter llmRouter) {
        this.config = config;
        this.prService = prService;
        this.publisher = publisher;
        this.astAnalyzer = astAnalyzer;
        this.llmRouter = llmRouter;
    }

    /**
     * Runs the full review pipeline for the configured PR.
     *
     * @return 0 on success, 1 on failure
     */
    public int review() {
        long startTime = System.currentTimeMillis();

        try {
            // Step 1: Fetch changed files
            LOG.info("═══════════════════════════════════════════════════");
            LOG.info("Starting AI code review for PR #{}", config.getPullRequestNumber());
            LOG.info("Repository: {}", config.getRepository());
            LOG.info("Head SHA: {}", config.getHeadSha());
            LOG.info("═══════════════════════════════════════════════════");

            List<PullRequestService.ChangedFile> changedFiles =
                    prService.getChangedFiles(config.getPullRequestNumber());

            if (changedFiles.isEmpty()) {
                LOG.info("No reviewable files found in PR #{} — exiting", config.getPullRequestNumber());
                return 0;
            }

            // Step 2: Create the context builder (needs the full file list)
            ContextBuilder contextBuilder = new ContextBuilder(
                    prService, astAnalyzer, config.getHeadSha(), changedFiles);

            // Step 3: Review each file concurrently using virtual threads
            ReviewResult result = reviewFilesConcurrently(changedFiles, contextBuilder);

            // Step 4: Publish the review
            LOG.info("───────────────────────────────────────────────────");
            LOG.info("Review complete. Publishing results and exporting metrics...");

            publisher.publishReview(
                    config.getPullRequestNumber(),
                    config.getHeadSha(),
                    result,
                    config.getSeverityThreshold()
            );

            // Step 5: Export structured review metrics for dashboard
            dev.codereviewer.metrics.ReviewMetricsExporter.export(config, result.getFileResults());

            long elapsed = System.currentTimeMillis() - startTime;
            LOG.info("═══════════════════════════════════════════════════");
            LOG.info("AI Code Review finished in {}s", String.format("%.1f", elapsed / 1000.0));
            LOG.info("  Files reviewed: {}", changedFiles.size() - result.getSkippedFiles().size());
            LOG.info("  Files skipped:  {}", result.getSkippedFiles().size());
            LOG.info("  Total findings: {}", result.getFindings().size());
            LOG.info("═══════════════════════════════════════════════════");

            return 0;

        } catch (Exception e) {
            LOG.error("Review pipeline failed: {}", e.getMessage(), e);
            return 1;
        }
    }

    /**
     * Reviews all files concurrently using virtual threads.
     */
    private ReviewResult reviewFilesConcurrently(
            List<PullRequestService.ChangedFile> changedFiles,
            ContextBuilder contextBuilder) throws InterruptedException {

        ReviewResult result = new ReviewResult();

        // Use virtual threads for concurrent LLM calls
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            // Submit all file review tasks
            List<Future<FileReviewTask.Result>> futures = new ArrayList<>();

            for (PullRequestService.ChangedFile file : changedFiles) {
                FileReviewTask task = new FileReviewTask(
                        file, prService, astAnalyzer, contextBuilder,
                        llmRouter, config.getHeadSha()
                );
                futures.add(executor.submit(task));
            }

            // Collect results
            for (Future<FileReviewTask.Result> future : futures) {
                try {
                    FileReviewTask.Result fileResult = future.get(120, TimeUnit.SECONDS);
                    result.addFileResult(fileResult);
                } catch (TimeoutException e) {
                    LOG.warn("File review timed out (120s limit)");
                    future.cancel(true);
                } catch (ExecutionException e) {
                    LOG.error("File review task failed: {}", e.getCause().getMessage());
                }
            }
        }

        return result;
    }
}
