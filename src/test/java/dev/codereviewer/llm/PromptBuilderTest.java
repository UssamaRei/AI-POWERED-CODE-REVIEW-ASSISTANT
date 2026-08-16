package dev.codereviewer.llm;

import dev.codereviewer.parser.CodeContext;
import dev.codereviewer.parser.DiffParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PromptBuilder}.
 */
class PromptBuilderTest {

    @Test
    @DisplayName("System prompt contains required elements")
    void systemPromptContainsRequirements() {
        String prompt = PromptBuilder.buildSystemPrompt();

        // Must instruct JSON output
        assertTrue(prompt.contains("JSON"), "Should mention JSON output format");
        assertTrue(prompt.contains("findings"), "Should mention findings array");

        // Must define severities
        assertTrue(prompt.contains("CRITICAL"));
        assertTrue(prompt.contains("WARNING"));
        assertTrue(prompt.contains("SUGGESTION"));
        assertTrue(prompt.contains("NITPICK"));

        // Must instruct to only review changed code
        assertTrue(prompt.toLowerCase().contains("changed"));

        // Must mention key review areas
        assertTrue(prompt.toLowerCase().contains("null"));
        assertTrue(prompt.toLowerCase().contains("security"));
    }

    @Test
    @DisplayName("File review prompt includes diff and structural context")
    void fileReviewPromptWithAstContext() {
        CodeContext context = new CodeContext(
                "src/main/java/com/example/UserService.java",
                "com.example",
                "UserService",
                List.of("java.util.Optional", "com.example.repo.UserRepository"),
                List.of(
                        new CodeContext.MethodSignature(
                                "findById", "Optional<User>",
                                List.of("long"), List.of("Override"),
                                14, 16, "public Optional<User> findById(long id) {\n    return repo.find(id);\n}")
                ),
                List.of(
                        new CodeContext.MethodSignature(
                                "findById", "Optional<User>",
                                List.of("long"), List.of("Override"),
                                14, 16, "public Optional<User> findById(long id) {\n    return repo.find(id);\n}")
                ),
                List.of(new CodeContext.FieldInfo("repo", "UserRepository", List.of(), 10)),
                "BaseService",
                List.of("Auditable"),
                "@@ -14,3 +14,3 @@\n-old\n+new",
                List.of(),
                true
        );

        String prompt = PromptBuilder.buildFileReviewPrompt(context, "// Related API\ninterface Auditable {}");

        // Must include file path
        assertTrue(prompt.contains("UserService.java"));

        // Must include structural info
        assertTrue(prompt.contains("com.example"));
        assertTrue(prompt.contains("UserService"));
        assertTrue(prompt.contains("BaseService"));
        assertTrue(prompt.contains("Auditable"));

        // Must include the diff
        assertTrue(prompt.contains("@@ -14,3 +14,3 @@"));

        // Must include changed method body
        assertTrue(prompt.contains("findById"));
        assertTrue(prompt.contains("Optional<User>"));

        // Must include related context
        assertTrue(prompt.contains("Related Context"));
        assertTrue(prompt.contains("interface Auditable"));
    }

    @Test
    @DisplayName("File review prompt works with fallback (no AST)")
    void fileReviewPromptWithoutAst() {
        CodeContext context = CodeContext.fallback(
                "BrokenFile.java",
                "@@ -1,2 +1,3 @@\n old\n+new\n old2",
                List.of()
        );

        String prompt = PromptBuilder.buildFileReviewPrompt(context, "");

        // Must still include the diff
        assertTrue(prompt.contains("BrokenFile.java"));
        assertTrue(prompt.contains("@@ -1,2 +1,3 @@"));

        // Should NOT include structure section since parse failed
        assertFalse(prompt.contains("### Structure"));
    }
}
