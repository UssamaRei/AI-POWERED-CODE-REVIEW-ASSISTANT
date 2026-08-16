package dev.codereviewer.llm;

import dev.codereviewer.parser.CodeContext;
import dev.codereviewer.parser.DiffParser;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Constructs the LLM prompts for code review.
 *
 * <p>The prompt strategy is designed to maximize the quality of the LLM's
 * review output:
 * <ul>
 *   <li><b>System prompt:</b> Sets the reviewer persona, output format (JSON),
 *       and severity definitions — stays constant across files</li>
 *   <li><b>User prompt:</b> File-specific — includes the diff, structural
 *       context (AST data), and related code snippets</li>
 * </ul>
 *
 * <p>The JSON output format instruction is critical: by telling the LLM to
 * return a specific JSON schema and also setting {@code responseMimeType}
 * to "application/json" in the API call, we get reliable structured output
 * without regex parsing.
 */
public final class PromptBuilder {

    private PromptBuilder() {
        // utility class
    }

    /**
     * Returns the system prompt that instructs the LLM on its role and output format.
     */
    public static String buildSystemPrompt() {
        return """
                You are a senior Java code reviewer performing a thorough code review on a GitHub pull request.
                
                ## Your Responsibilities
                - Identify bugs, potential null pointer exceptions, resource leaks, and concurrency issues
                - Flag security vulnerabilities (SQL injection, XSS, path traversal, etc.)
                - Point out performance concerns (unnecessary allocations, N+1 queries, etc.)
                - Suggest improvements to code clarity, naming, and design patterns
                - Check for missing error handling and edge cases
                - Verify API contract compliance (interface/abstract method implementations)
                
                ## Rules
                - ONLY comment on the CHANGED code (lines in the diff). Do not review unchanged code.
                - Be CONCISE and ACTIONABLE. Say what's wrong and how to fix it.
                - Do NOT state obvious things ("this method takes a String parameter").
                - Do NOT praise code. Only report findings that need attention.
                - If the code looks good and you have no findings, return an empty array [].
                - Each finding must reference a specific line number in the NEW file (post-change).
                
                ## Severity Definitions
                - CRITICAL: Will cause a bug, crash, security vulnerability, or data loss in production.
                - WARNING: Likely to cause issues under certain conditions, or violates important best practices.
                - SUGGESTION: Would improve code quality, readability, or maintainability but isn't a bug.
                - NITPICK: Minor style/formatting preference. Low priority.
                
                ## Output Format
                Return a JSON object with a single key "findings" containing an array. Each finding must have:
                {
                  "findings": [
                    {
                      "file": "path/to/File.java",
                      "line": 42,
                      "severity": "WARNING",
                      "category": "null-safety",
                      "comment": "Concise description of the issue and suggested fix."
                    }
                  ]
                }
                
                Categories to use: null-safety, concurrency, resource-leak, security, performance,
                error-handling, naming, design, documentation, testing, api-contract, deprecated-usage.
                """;
    }

    /**
     * Builds the per-file user prompt with diff and structural context.
     *
     * @param context        the CodeContext for the file being reviewed
     * @param relatedContext additional context from related files (may be empty)
     * @return the user prompt string
     */
    public static String buildFileReviewPrompt(CodeContext context, String relatedContext) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("## File Under Review: `").append(context.filePath()).append("`\n\n");

        // Structural info (if AST parse succeeded)
        if (context.parseSucceeded()) {
            prompt.append("### Structure\n");
            if (context.packageName() != null) {
                prompt.append("- Package: `").append(context.packageName()).append("`\n");
            }
            if (context.className() != null) {
                prompt.append("- Class: `").append(context.className()).append("`\n");
            }
            if (context.superClass() != null) {
                prompt.append("- Extends: `").append(context.superClass()).append("`\n");
            }
            if (!context.interfaces().isEmpty()) {
                prompt.append("- Implements: ").append(String.join(", ",
                        context.interfaces().stream().map(i -> "`" + i + "`").toList())).append("\n");
            }
            prompt.append("\n");

            // Changed methods (the focus of the review)
            if (!context.changedMethods().isEmpty()) {
                prompt.append("### Changed Methods\n");
                for (CodeContext.MethodSignature method : context.changedMethods()) {
                    prompt.append("#### `").append(method.toSignatureString()).append("`");
                    if (!method.annotations().isEmpty()) {
                        prompt.append(" (").append(String.join(", ",
                                method.annotations().stream().map(a -> "@" + a).toList())).append(")");
                    }
                    prompt.append(" [lines ").append(method.startLine()).append("-").append(method.endLine()).append("]\n");

                    if (method.body() != null) {
                        prompt.append("```java\n").append(method.body()).append("```\n\n");
                    }
                }
            }

            // All method signatures (for context, without bodies)
            if (context.methods().size() > context.changedMethods().size()) {
                prompt.append("### Other Methods in Class (unchanged, for context)\n");
                for (CodeContext.MethodSignature method : context.methods()) {
                    if (!context.changedMethods().contains(method)) {
                        prompt.append("- `").append(method.toSignatureString()).append("`\n");
                    }
                }
                prompt.append("\n");
            }
        }

        // The diff (always included — this is the primary input)
        prompt.append("### Diff\n```diff\n").append(context.rawDiff()).append("\n```\n\n");

        // Related context from other files
        if (relatedContext != null && !relatedContext.isBlank()) {
            prompt.append("### Related Context (from other files in this PR/repo)\n");
            prompt.append(relatedContext).append("\n\n");
        }

        prompt.append("Review the changed code above. Return your findings as the specified JSON format.\n");

        return prompt.toString();
    }
}
