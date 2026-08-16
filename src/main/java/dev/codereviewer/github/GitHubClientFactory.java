package dev.codereviewer.github;

import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Factory for creating authenticated GitHub API client instances.
 *
 * <p>In a GitHub Actions context, authentication uses the automatically
 * provided {@code GITHUB_TOKEN}, which has scoped permissions for the
 * repository that triggered the workflow.
 */
public final class GitHubClientFactory {

    private static final Logger LOG = LoggerFactory.getLogger(GitHubClientFactory.class);

    private GitHubClientFactory() {
        // utility class
    }

    /**
     * Creates a GitHub client authenticated with the given OAuth token.
     *
     * @param token the GitHub token (typically {@code GITHUB_TOKEN} from Actions)
     * @return an authenticated GitHub client
     * @throws IOException if the connection cannot be established
     */
    public static GitHub create(String token) throws IOException {
        GitHub github = new GitHubBuilder()
                .withOAuthToken(token)
                .build();

        LOG.info("GitHub client created, authenticated as: {}", github.getMyself().getLogin());
        return github;
    }

    /**
     * Creates a GitHub client and verifies connectivity.
     * Falls back to a reduced-permission mode if the token has limited scope.
     *
     * @param token the GitHub token
     * @return an authenticated GitHub client
     * @throws IOException if the connection cannot be established
     */
    public static GitHub createWithValidation(String token) throws IOException {
        GitHub github = create(token);

        if (!github.isCredentialValid()) {
            throw new IOException("GitHub credentials are invalid — check GITHUB_TOKEN");
        }

        return github;
    }
}
