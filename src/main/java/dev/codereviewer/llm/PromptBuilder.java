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
     * Returns the system prompt that instructs the LLM on its role and output format for a specific language.
     */
    public static String buildSystemPrompt(String language) {
        String lang = (language != null && !language.isBlank()) ? language : "Code";
        String langGuidelines = getLanguageSpecificGuidelines(lang);

        return """
                You are an expert senior %s reviewer performing a thorough, high-standard code review on a GitHub pull request.
                
                ## Your Responsibilities
                - Identify bugs, null/undefined reference errors, resource leaks, and concurrency/race conditions
                - Flag security vulnerabilities (SQL injection, XSS, CSRF, command injection, insecure deserialization)
                - Point out performance concerns (unnecessary allocations, inefficient queries, blocking I/O in async contexts)
                - Suggest improvements to code clarity, naming, and language-idiomatic design patterns
                - Check for missing error handling, boundary checks, and edge cases
                %s
                ## Rules
                - ONLY comment on the CHANGED code (lines in the diff). Do not review unchanged code.
                - Be CONCISE and ACTIONABLE. Say what's wrong and provide a concrete fix.
                - Do NOT state obvious things ("this method takes a parameter").
                - Do NOT praise code. Only report findings that need attention.
                - If the code looks good and you have no findings, return an empty array [].
                - Each finding must reference a specific line number in the NEW file (post-change).
                
                ## Severity Definitions
                - CRITICAL: Will cause a crash, bug, security exploit, or data corruption in production.
                - WARNING: Likely to cause issues under certain conditions, resource leak, or violates critical idioms.
                - SUGGESTION: Improves maintainability, performance, or readability.
                - NITPICK: Minor style/naming preference.
                
                ## Output Format
                Return a JSON object with a single key "findings" containing an array. Each finding must have:
                {
                  "findings": [
                    {
                      "file": "path/to/file",
                      "line": 42,
                      "severity": "WARNING",
                      "category": "security",
                      "comment": "Concise description of the issue and suggested fix."
                    }
                  ]
                }
                
                Categories to use: security, null-safety, resource-leak, concurrency, performance,
                error-handling, naming, design, documentation, testing, api-contract, deprecated-usage.
                """.formatted(lang, langGuidelines);
    }

    /**
     * Default system prompt (Java-oriented).
     */
    public static String buildSystemPrompt() {
        return buildSystemPrompt("Java");
    }

    private static String getLanguageSpecificGuidelines(String language) {
        return switch (language.toLowerCase()) {
            case "c#", "csharp" -> """
                - Check for IDisposable leaks (missing `using` declarations or statements)
                - Detect async/await anti-patterns (avoid `async void`, avoid blocking `.Result`/`.Wait()`)
                - Ensure proper LINQ usage (avoid multiple enumerations of IEnumerable)
                - Check nullable reference types and null-forgiving operators
                """;
            case "python" -> """
                - Ensure context managers (`with` statements) for file and connection management
                - Check for mutable default arguments in function definitions
                - Enforce specific exception handling rather than bare `except:`
                - Check for type annotation consistency and asyncio coroutine handling
                """;
            case "typescript", "javascript", "typescript (react)", "javascript (react)" -> """
                - Avoid unsafe `any` types; enforce strict typing in TypeScript
                - Prevent unhandled Promise rejections and floating async promises
                - Check for memory leaks in event listeners, closures, and React hooks (useEffect dependencies)
                - Watch for prototype pollution and XSS risks in DOM manipulation
                """;
            case "go" -> """
                - Prevent goroutine leaks and race conditions
                - Check for `defer` statements inside tight loops
                - Ensure errors are checked explicitly, not discarded with `_`
                - Check proper sync.Mutex locking/unlocking patterns
                """;
            case "rust" -> """
                - Scrutinize `unsafe` blocks
                - Avoid unnecessary `.clone()` and allocation bottlenecks
                - Suggest idiomatic error propagation using `?` instead of `.unwrap()`
                """;
            case "java" -> """
                - Enforce `try-with-resources` for AutoCloseable resources
                - Avoid string concatenation in loops (suggest `StringBuilder`)
                - Check for `NullPointerException` risks and optional usage
                - Verify `equals()` and `hashCode()` contracts and String comparison using `.equals()`
                """;
            default -> "- Enforce language-idiomatic clean code, security best practices, and resource safety\n";
        };
    }

    /**
     * Builds the per-file user prompt with diff and structural context.
     *
     * @param context        the CodeContext for the file being reviewed
     * @param relatedContext additional context from related files (may be empty)
     * @return the user prompt string
     */
    public static String buildFileReviewPrompt(CodeContext context, String relatedContext) {
        return buildFileReviewPrompt(context, relatedContext, null);
    }

    /**
     * Builds the per-file user prompt with diff, language, and structural context.
     */
    public static String buildFileReviewPrompt(CodeContext context, String relatedContext, String language) {
        StringBuilder prompt = new StringBuilder();

        String langName = (language != null && !language.isBlank())
                ? language
                : dev.codereviewer.util.LanguageDetector.detectLanguage(context.filePath());

        prompt.append("## File Under Review: `").append(context.filePath()).append("` (").append(langName).append(")\n\n");

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
