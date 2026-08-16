package dev.codereviewer.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LanguageDetector Tests")
class LanguageDetectorTest {

    @ParameterizedTest(name = "{0} should be detected as {1}")
    @CsvSource({
            "src/main/UserService.java, Java",
            "Controllers/AuthController.cs, C#",
            "scripts/deploy.py, Python",
            "frontend/src/App.tsx, TypeScript (React)",
            "frontend/src/index.ts, TypeScript",
            "frontend/src/utils.js, JavaScript",
            "server/main.go, Go",
            "engine/src/lib.rs, Rust",
            "api/index.php, PHP",
            "queries/analytics.sql, SQL",
            "ios/AppView.swift, Swift",
            "android/MainActivity.kt, Kotlin"
    })
    void testDetectLanguage(String filePath, String expectedLanguage) {
        assertEquals(expectedLanguage, LanguageDetector.detectLanguage(filePath));
    }

    @Test
    @DisplayName("isReviewable with 'all' should allow all valid code files")
    void testReviewableAll() {
        assertTrue(LanguageDetector.isReviewable("Service.java", "all"));
        assertTrue(LanguageDetector.isReviewable("Program.cs", "all"));
        assertTrue(LanguageDetector.isReviewable("app.py", "all"));
        assertTrue(LanguageDetector.isReviewable("component.tsx", "all"));
        assertTrue(LanguageDetector.isReviewable("main.go", "all"));
    }

    @Test
    @DisplayName("isReviewable should filter by specific or comma-separated languages")
    void testReviewableFiltered() {
        assertTrue(LanguageDetector.isReviewable("Service.java", "java,csharp"));
        assertTrue(LanguageDetector.isReviewable("Program.cs", "java,csharp"));
        assertFalse(LanguageDetector.isReviewable("app.py", "java,csharp"));

        // Single language filter with alias
        assertTrue(LanguageDetector.isReviewable("Program.cs", "c#"));
        assertTrue(LanguageDetector.isReviewable("Program.cs", "cs"));
        assertTrue(LanguageDetector.isReviewable("app.py", "python"));
        assertFalse(LanguageDetector.isReviewable("Service.java", "python"));
    }

    @Test
    @DisplayName("isReviewable should ignore bundles and generated artifacts")
    void testIgnoredFiles() {
        assertFalse(LanguageDetector.isReviewable("dist/bundle.min.js", "all"));
        assertFalse(LanguageDetector.isReviewable("package-lock.json", "all"));
        assertFalse(LanguageDetector.isReviewable("yarn.lock", "all"));
        assertFalse(LanguageDetector.isReviewable("node_modules/express/index.js", "all"));
        assertFalse(LanguageDetector.isReviewable("bin/Debug/net8.0/app.dll", "all"));
    }
}
