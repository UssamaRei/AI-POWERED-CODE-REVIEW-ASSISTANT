package dev.codereviewer.github;

import org.kohsuke.github.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for fetching pull request data from GitHub.
 *
 * <p>Retrieves changed files, diffs, and file contents needed by the
 * review pipeline. Filters to relevant files and respects the max-files cap.
 */
public class PullRequestService {

    private static final Logger LOG = LoggerFactory.getLogger(PullRequestService.class);

    private final GHRepository repository;
    private final String reviewLanguage;
    private final int maxFiles;

    public PullRequestService(GHRepository repository, String reviewLanguage, int maxFiles) {
        this.repository = repository;
        this.reviewLanguage = reviewLanguage;
        this.maxFiles = maxFiles;
    }

    /**
     * Fetches the list of changed files in the given PR, filtered by language
     * and capped at maxFiles.
     *
     * @param prNumber the pull request number
     * @return list of changed file details (path, patch, status)
     * @throws IOException if the GitHub API call fails
     */
    public List<ChangedFile> getChangedFiles(int prNumber) throws IOException {
        GHPullRequest pr = repository.getPullRequest(prNumber);
        List<GHPullRequestFileDetail> allFiles = pr.listFiles().toList();

        LOG.info("PR #{} has {} changed files total", prNumber, allFiles.size());

        List<ChangedFile> reviewableFiles = new ArrayList<>();
        List<String> skippedFiles = new ArrayList<>();

        for (GHPullRequestFileDetail file : allFiles) {
            String filename = file.getFilename();

            // Filter by file extension based on review language
            if (!isReviewableFile(filename)) {
                skippedFiles.add(filename);
                continue;
            }

            // Skip deleted files (nothing to review)
            if ("removed".equals(file.getStatus())) {
                skippedFiles.add(filename + " (deleted)");
                continue;
            }

            // Skip binary files or files with no patch
            if (file.getPatch() == null || file.getPatch().isBlank()) {
                skippedFiles.add(filename + " (binary/no-patch)");
                continue;
            }

            reviewableFiles.add(new ChangedFile(
                    filename,
                    file.getPatch(),
                    file.getStatus(),
                    file.getAdditions(),
                    file.getDeletions(),
                    file.getChanges()
            ));

            if (reviewableFiles.size() >= maxFiles) {
                LOG.warn("Reached max file limit ({}), remaining files will be skipped", maxFiles);
                break;
            }
        }

        if (!skippedFiles.isEmpty()) {
            LOG.info("Skipped {} non-reviewable files: {}", skippedFiles.size(),
                    skippedFiles.size() <= 5 ? skippedFiles : skippedFiles.subList(0, 5) + "...");
        }

        LOG.info("Will review {} files", reviewableFiles.size());
        return reviewableFiles;
    }

    /**
     * Fetches the full content of a file at the given ref (commit SHA or branch).
     *
     * @param filePath path relative to repo root
     * @param ref      git ref (SHA, branch name, tag)
     * @return file content as string, or null if the file cannot be read
     */
    public String getFileContent(String filePath, String ref) {
        try {
            GHContent content = repository.getFileContent(filePath, ref);
            return content.getContent();
        } catch (IOException e) {
            LOG.warn("Could not fetch content for {} at {}: {}", filePath, ref, e.getMessage());
            return null;
        }
    }

    /**
     * Gets the GHPullRequest object for direct access (e.g., by ReviewPublisher).
     */
    public GHPullRequest getPullRequest(int prNumber) throws IOException {
        return repository.getPullRequest(prNumber);
    }

    /**
     * Determines if a file should be reviewed based on its extension and the configured language filter.
     */
    private boolean isReviewableFile(String filename) {
        return dev.codereviewer.util.LanguageDetector.isReviewable(filename, reviewLanguage);
    }

    /**
     * Represents a changed file with its diff patch and metadata.
     */
    public record ChangedFile(
            String filename,
            String patch,      // unified diff patch from GitHub
            String status,     // "added", "modified", "renamed"
            int additions,
            int deletions,
            int changes
    ) {}
}
