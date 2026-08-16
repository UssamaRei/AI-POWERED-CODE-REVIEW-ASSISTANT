package dev.codereviewer.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable configuration loaded from GitHub Actions environment variables and inputs.
 *
 * <p>GitHub Actions automatically provides:
 * <ul>
 *   <li>{@code GITHUB_TOKEN} — auth token for the GitHub API</li>
 *   <li>{@code GITHUB_REPOSITORY} — owner/repo format</li>
 *   <li>{@code GITHUB_EVENT_PATH} — path to the JSON event payload</li>
 * </ul>
 *
 * <p>Action inputs are prefixed with {@code INPUT_} by GitHub Actions at runtime.
 */
public final class ReviewConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewConfig.class);

    // --- GitHub-provided environment ---
    private final String githubToken;
    private final String repository;      // "owner/repo"
    private final String repositoryOwner;
    private final String repositoryName;

    // --- Parsed from event payload ---
    private final int pullRequestNumber;
    private final String headSha;
    private final String baseRef;

    // --- Action inputs ---
    private final String geminiApiKey;
    private final String groqApiKey;       // nullable — fallback is optional
    private final String reviewLanguage;
    private final Severity severityThreshold;
    private final int maxFiles;

    // --- Workspace ---
    private final String workspacePath;

    /**
     * Severity levels for review findings, ordered from most to least severe.
     */
    public enum Severity {
        CRITICAL, WARNING, SUGGESTION, NITPICK;

        /**
         * Returns true if this severity is at or above the given threshold.
         */
        public boolean meetsThreshold(Severity threshold) {
            return this.ordinal() <= threshold.ordinal();
        }
    }

    private ReviewConfig(Builder builder) {
        this.githubToken = Objects.requireNonNull(builder.githubToken, "GITHUB_TOKEN is required");
        this.repository = Objects.requireNonNull(builder.repository, "GITHUB_REPOSITORY is required");
        this.pullRequestNumber = builder.pullRequestNumber;
        this.headSha = Objects.requireNonNull(builder.headSha, "Head SHA is required");
        this.baseRef = Objects.requireNonNull(builder.baseRef, "Base ref is required");
        this.geminiApiKey = Objects.requireNonNull(builder.geminiApiKey, "Gemini API key is required");
        this.groqApiKey = builder.groqApiKey; // nullable
        this.reviewLanguage = builder.reviewLanguage != null ? builder.reviewLanguage : "all";
        this.severityThreshold = builder.severityThreshold != null ? builder.severityThreshold : Severity.SUGGESTION;
        this.maxFiles = builder.maxFiles > 0 ? builder.maxFiles : 15;
        this.workspacePath = builder.workspacePath != null ? builder.workspacePath : ".";

        // Derive owner/name from "owner/repo"
        String[] parts = this.repository.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("GITHUB_REPOSITORY must be in 'owner/repo' format, got: " + this.repository);
        }
        this.repositoryOwner = parts[0];
        this.repositoryName = parts[1];

        if (this.pullRequestNumber <= 0) {
            throw new IllegalArgumentException("Pull request number must be positive, got: " + this.pullRequestNumber);
        }
    }

    /**
     * Creates a ReviewConfig by reading from the actual GitHub Actions environment.
     * This is the primary factory method used at runtime.
     */
    public static ReviewConfig fromEnvironment() throws IOException {
        Builder builder = new Builder();

        // GitHub-provided env vars (supports GITHUB_TOKEN or INPUT_GITHUB_TOKEN)
        String token = getEnv("GITHUB_TOKEN", null);
        if (token == null || token.isBlank()) {
            token = getEnv("INPUT_GITHUB_TOKEN", null);
        }
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Required environment variable not set: GITHUB_TOKEN (or input 'github_token')");
        }
        builder.githubToken = token;
        builder.repository = requireEnv("GITHUB_REPOSITORY");
        builder.workspacePath = getEnv("GITHUB_WORKSPACE", ".");

        // Parse the event payload to extract PR details
        String eventPath = requireEnv("GITHUB_EVENT_PATH");
        parseEventPayload(builder, eventPath);

        // Action inputs (GitHub prefixes them with INPUT_ and uppercases)
        builder.geminiApiKey = requireEnv("INPUT_GEMINI_API_KEY");
        builder.groqApiKey = getEnv("INPUT_GROQ_API_KEY", null);
        builder.reviewLanguage = getEnv("INPUT_REVIEW_LANGUAGE", "all");
        builder.maxFiles = parseIntEnv("INPUT_MAX_FILES", 15);

        String threshold = getEnv("INPUT_SEVERITY_THRESHOLD", "SUGGESTION");
        try {
            builder.severityThreshold = Severity.valueOf(threshold.toUpperCase());
        } catch (IllegalArgumentException e) {
            LOG.warn("Invalid severity threshold '{}', defaulting to SUGGESTION", threshold);
            builder.severityThreshold = Severity.SUGGESTION;
        }

        ReviewConfig config = new ReviewConfig(builder);
        LOG.info("Configuration loaded: repo={}, PR=#{}, headSha={}, language={}, threshold={}, maxFiles={}",
                config.repository, config.pullRequestNumber, config.headSha,
                config.reviewLanguage, config.severityThreshold, config.maxFiles);

        return config;
    }

    /**
     * Parses the GitHub event JSON payload to extract PR number, head SHA, and base ref.
     */
    private static void parseEventPayload(Builder builder, String eventPath) throws IOException {
        Path path = Path.of(eventPath);
        if (!Files.exists(path)) {
            throw new IOException("Event payload file not found: " + eventPath);
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(Files.readString(path));

        JsonNode prNode = root.get("pull_request");
        if (prNode == null) {
            throw new IOException("Event payload does not contain 'pull_request' — is this triggered by a pull_request event?");
        }

        builder.pullRequestNumber = root.get("number").asInt();
        builder.headSha = prNode.get("head").get("sha").asText();
        builder.baseRef = prNode.get("base").get("ref").asText();
    }

    // --- Environment helpers ---

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable not set: " + name);
        }
        return value;
    }

    private static String getEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    private static int parseIntEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            LOG.warn("Invalid integer for '{}': '{}', using default {}", name, value, defaultValue);
            return defaultValue;
        }
    }

    // --- Getters ---

    public String getGithubToken()       { return githubToken; }
    public String getRepository()        { return repository; }
    public String getRepositoryOwner()   { return repositoryOwner; }
    public String getRepositoryName()    { return repositoryName; }
    public int getPullRequestNumber()    { return pullRequestNumber; }
    public String getHeadSha()           { return headSha; }
    public String getBaseRef()           { return baseRef; }
    public String getGeminiApiKey()      { return geminiApiKey; }
    public String getGroqApiKey()        { return groqApiKey; }
    public boolean hasGroqFallback()     { return groqApiKey != null && !groqApiKey.isBlank(); }
    public String getReviewLanguage()    { return reviewLanguage; }
    public Severity getSeverityThreshold() { return severityThreshold; }
    public int getMaxFiles()             { return maxFiles; }
    public String getWorkspacePath()     { return workspacePath; }

    @Override
    public String toString() {
        return "ReviewConfig{" +
                "repository='" + repository + '\'' +
                ", PR=#" + pullRequestNumber +
                ", headSha='" + headSha + '\'' +
                ", language='" + reviewLanguage + '\'' +
                ", threshold=" + severityThreshold +
                ", maxFiles=" + maxFiles +
                ", hasGroqFallback=" + hasGroqFallback() +
                '}';
    }

    // --- Builder (used by fromEnvironment and tests) ---

    public static class Builder {
        private String githubToken;
        private String repository;
        private int pullRequestNumber;
        private String headSha;
        private String baseRef;
        private String geminiApiKey;
        private String groqApiKey;
        private String reviewLanguage;
        private Severity severityThreshold;
        private int maxFiles;
        private String workspacePath;

        public Builder githubToken(String v)        { this.githubToken = v; return this; }
        public Builder repository(String v)         { this.repository = v; return this; }
        public Builder pullRequestNumber(int v)     { this.pullRequestNumber = v; return this; }
        public Builder headSha(String v)            { this.headSha = v; return this; }
        public Builder baseRef(String v)            { this.baseRef = v; return this; }
        public Builder geminiApiKey(String v)       { this.geminiApiKey = v; return this; }
        public Builder groqApiKey(String v)         { this.groqApiKey = v; return this; }
        public Builder reviewLanguage(String v)     { this.reviewLanguage = v; return this; }
        public Builder severityThreshold(Severity v){ this.severityThreshold = v; return this; }
        public Builder maxFiles(int v)              { this.maxFiles = v; return this; }
        public Builder workspacePath(String v)      { this.workspacePath = v; return this; }

        public ReviewConfig build() {
            return new ReviewConfig(this);
        }
    }
}
