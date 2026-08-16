package dev.codereviewer.review;

import dev.codereviewer.context.ContextBuilder;
import dev.codereviewer.context.ContextChunker;
import dev.codereviewer.github.PullRequestService;
import dev.codereviewer.llm.*;
import dev.codereviewer.parser.AstAnalyzer;
import dev.codereviewer.parser.CodeContext;
import dev.codereviewer.parser.DiffParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Reviews a single changed file — encapsulates the full per-file pipeline:
 * parse diff → analyze AST → gather context → build prompt → call LLM → parse response.
 *
 * <p>Designed to run as a {@link Callable} in a virtual thread so multiple files
 * can be reviewed concurrently. All exceptions are caught internally so one
 * file's failure doesn't block others.
 *
 * @see ReviewOrchestrator
 */
public class FileReviewTask implements Callable<FileReviewTask.Result> {

    private static final Logger LOG = LoggerFactory.getLogger(FileReviewTask.class);

    private final PullRequestService.ChangedFile changedFile;
    private final PullRequestService prService;
    private final AstAnalyzer astAnalyzer;
    private final ContextBuilder contextBuilder;
    private final LlmRouter llmRouter;
    private final String headSha;

    public FileReviewTask(PullRequestService.ChangedFile changedFile,
                          PullRequestService prService,
                          AstAnalyzer astAnalyzer,
                          ContextBuilder contextBuilder,
                          LlmRouter llmRouter,
                          String headSha) {
        this.changedFile = changedFile;
        this.prService = prService;
        this.astAnalyzer = astAnalyzer;
        this.contextBuilder = contextBuilder;
        this.llmRouter = llmRouter;
        this.headSha = headSha;
    }

    @Override
    public Result call() {
        String filename = changedFile.filename();
        LOG.info("▶ Reviewing: {}", filename);

        try {
            // Step 1: Parse the diff into structured hunks
            List<DiffParser.DiffHunk> hunks = DiffParser.parse(changedFile.patch());

            // Step 2: Fetch the full file content at HEAD for AST analysis
            String sourceCode = prService.getFileContent(filename, headSha);

            // Step 3: Analyze the AST (graceful fallback if parsing fails)
            CodeContext codeContext = astAnalyzer.analyze(
                    sourceCode, filename, changedFile.patch(), hunks);

            // Step 4: Gather related context (imports, hierarchy)
            String relatedContext = contextBuilder.buildRelatedContext(codeContext);

            // Step 5: Fit context to token limits
            String methodBodies = codeContext.changedMethods().stream()
                    .filter(m -> m.body() != null)
                    .map(CodeContext.MethodSignature::body)
                    .collect(Collectors.joining("\n\n"));

            relatedContext = ContextChunker.fitToLimit(
                    changedFile.patch(), methodBodies, relatedContext);

            // Step 6: Build the LLM prompt
            String systemPrompt = PromptBuilder.buildSystemPrompt();
            String userPrompt = PromptBuilder.buildFileReviewPrompt(codeContext, relatedContext);

            // Step 7: Call the LLM
            String llmResponse = llmRouter.chat(systemPrompt, userPrompt);

            if (llmResponse == null) {
                String error = llmRouter.getLastError() != null ? llmRouter.getLastError() : "LLM call failed";
                LOG.warn("✗ {} — {}", filename, error);
                return Result.skipped(filename, error);
            }

            // Step 8: Parse the LLM response into findings
            List<ReviewFinding> findings = ResponseParser.parse(llmResponse, filename);

            LOG.info("✓ {} — {} finding(s) [AST: {}]",
                    filename, findings.size(),
                    codeContext.parseSucceeded() ? "✓" : "✗ (fallback)");

            return Result.success(filename, findings);

        } catch (Exception e) {
            LOG.error("✗ {} — unexpected error: {}", filename, e.getMessage(), e);
            return Result.skipped(filename, e.getMessage());
        }
    }

    /**
     * Result of a single file review.
     */
    public record Result(
            String filename,
            List<ReviewFinding> findings,
            boolean success,
            String skipReason
    ) {
        public static Result success(String filename, List<ReviewFinding> findings) {
            return new Result(filename, findings, true, null);
        }

        public static Result skipped(String filename, String reason) {
            return new Result(filename, List.of(), false, reason);
        }
    }
}
