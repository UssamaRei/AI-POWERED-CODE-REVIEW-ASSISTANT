package dev.codereviewer.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AstAnalyzer}.
 */
class AstAnalyzerTest {

    private AstAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        // No workspace path — symbol resolution won't find project files,
        // but basic parsing and JDK type resolution still works
        analyzer = new AstAnalyzer(null);
    }

    @Test
    @DisplayName("Parses a simple Java class and extracts structure")
    void parseSimpleClass() {
        String source = """
                package com.example.service;
                
                import java.util.List;
                import java.util.Optional;
                
                public class UserService extends BaseService implements Auditable {
                
                    private final UserRepository repo;
                
                    public UserService(UserRepository repo) {
                        this.repo = repo;
                    }
                
                    public Optional<User> findById(long id) {
                        return Optional.ofNullable(repo.findById(id));
                    }
                
                    public List<User> findAll() {
                        return repo.findAll();
                    }
                }
                """;

        String patch = "@@ -14,3 +14,3 @@\n-    public User findById(long id) {\n+    public Optional<User> findById(long id) {\n";
        List<DiffParser.DiffHunk> hunks = DiffParser.parse(patch);

        CodeContext ctx = analyzer.analyze(source, "UserService.java", patch, hunks);

        assertTrue(ctx.parseSucceeded());
        assertEquals("com.example.service", ctx.packageName());
        assertEquals("UserService", ctx.className());
        assertEquals("BaseService", ctx.superClass());
        assertTrue(ctx.interfaces().contains("Auditable"));
        assertEquals(2, ctx.imports().size());

        // Methods: constructor + findById + findAll = 3
        assertEquals(3, ctx.methods().size());

        // Fields
        assertEquals(1, ctx.fields().size());
        assertEquals("repo", ctx.fields().get(0).name());
    }

    @Test
    @DisplayName("Identifies changed methods by cross-referencing with diff hunks")
    void identifiesChangedMethods() {
        String source = """
                package com.example;
                
                public class Calculator {
                    public int add(int a, int b) {
                        return a + b;
                    }
                
                    public int multiply(int a, int b) {
                        return a * b;
                    }
                }
                """;

        // Diff that only touches the multiply method (lines 8-10)
        String patch = "@@ -8,3 +8,4 @@\n     public int multiply(int a, int b) {\n-        return a * b;\n+        // Added validation\n+        return Math.multiplyExact(a, b);\n     }";
        List<DiffParser.DiffHunk> hunks = DiffParser.parse(patch);

        CodeContext ctx = analyzer.analyze(source, "Calculator.java", patch, hunks);

        assertTrue(ctx.parseSucceeded());
        assertEquals(2, ctx.methods().size());

        // Only multiply should be in changedMethods
        assertEquals(1, ctx.changedMethods().size());
        assertEquals("multiply", ctx.changedMethods().get(0).name());
    }

    @Test
    @DisplayName("Falls back gracefully on syntax errors")
    void fallbackOnSyntaxError() {
        String source = """
                package com.example;
                
                public class Broken {
                    public void method( {  // syntax error
                        return;
                    }
                }
                """;

        String patch = "@@ -4,2 +4,2 @@\n-    public void method() {\n+    public void method( {";
        List<DiffParser.DiffHunk> hunks = DiffParser.parse(patch);

        CodeContext ctx = analyzer.analyze(source, "Broken.java", patch, hunks);

        assertFalse(ctx.parseSucceeded());
        assertEquals("Broken.java", ctx.filePath());
        assertNotNull(ctx.rawDiff());
        assertNotNull(ctx.hunks());
    }

    @Test
    @DisplayName("Falls back on null source code")
    void fallbackOnNullSource() {
        CodeContext ctx = analyzer.analyze(null, "Missing.java", "@@ -1 +1 @@\n+line", List.of());

        assertFalse(ctx.parseSucceeded());
        assertEquals("Missing.java", ctx.filePath());
    }

    @Test
    @DisplayName("Extracts method annotations")
    void extractsAnnotations() {
        String source = """
                package com.example;
                
                public class Controller {
                    @Override
                    @Deprecated
                    public String toString() {
                        return "Controller";
                    }
                }
                """;

        String patch = "@@ -5,4 +5,4 @@\n";
        List<DiffParser.DiffHunk> hunks = DiffParser.parse(patch);

        CodeContext ctx = analyzer.analyze(source, "Controller.java", patch, hunks);

        assertTrue(ctx.parseSucceeded());
        CodeContext.MethodSignature method = ctx.methods().get(0);
        assertTrue(method.annotations().contains("Override"));
        assertTrue(method.annotations().contains("Deprecated"));
    }
}
