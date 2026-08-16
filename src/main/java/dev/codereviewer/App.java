package dev.codereviewer;

import dev.codereviewer.config.ReviewConfig;
import dev.codereviewer.github.GitHubClientFactory;
import dev.codereviewer.github.PullRequestService;
import dev.codereviewer.github.ReviewPublisher;
import dev.codereviewer.llm.GeminiClient;
import dev.codereviewer.llm.GroqClient;
import dev.codereviewer.llm.LlmRouter;
import dev.codereviewer.parser.AstAnalyzer;
import dev.codereviewer.review.ReviewOrchestrator;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the AI Code Review Assistant.
 *
 * <p>This class wires all components together and runs the review pipeline.
 * It's designed to be invoked from a Docker container inside a GitHub Action:
 *
 * <pre>
 * java -jar app.jar
 * </pre>
 *
 * <p>All configuration comes from environment variables set by GitHub Actions
 * and the action inputs defined in {@code action.yml}.
 */
public class App {

    private static final Logger LOG = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        int exitCode;

        try {
            exitCode = run();
        } catch (Exception e) {
            LOG.error("Fatal error: {}", e.getMessage(), e);
            exitCode = 1;
        }

        System.exit(exitCode);
    }

    /**
     * Wires dependencies and runs the review pipeline.
     *
     * @return exit code (0 = success, 1 = failure)
     */
    static int run() throws Exception {
        LOG.info("AI Code Review Assistant starting...");

        // ── Step 1: Load configuration ──────────────────────────────
        ReviewConfig config = ReviewConfig.fromEnvironment();
        LOG.info("Config loaded: {}", config);

        // ── Step 2: Create GitHub client ────────────────────────────
        GitHub github = GitHubClientFactory.create(config.getGithubToken());
        GHRepository repository = github.getRepository(config.getRepository());
        LOG.info("Connected to repository: {}", repository.getFullName());

        // ── Step 3: Create service layer ────────────────────────────
        PullRequestService prService = new PullRequestService(
                repository,
                config.getReviewLanguage(),
                config.getMaxFiles()
        );

        ReviewPublisher publisher = new ReviewPublisher(repository);

        // ── Step 4: Create code analysis layer ──────────────────────
        AstAnalyzer astAnalyzer = new AstAnalyzer(config.getWorkspacePath());

        // ── Step 5: Create LLM layer ────────────────────────────────
        GeminiClient gemini = new GeminiClient(config.getGeminiApiKey());

        GroqClient groq = config.hasGroqFallback()
                ? new GroqClient(config.getGroqApiKey())
                : null;

        LlmRouter llmRouter = new LlmRouter(gemini, groq);

        // ── Step 6: Wire and run the orchestrator ───────────────────
        ReviewOrchestrator orchestrator = new ReviewOrchestrator(
                config, prService, publisher, astAnalyzer, llmRouter
        );

        return orchestrator.review();
    }
}
