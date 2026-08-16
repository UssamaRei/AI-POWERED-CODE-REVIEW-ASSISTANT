package dev.codereviewer.util;

import java.util.*;

/**
 * Utility to detect programming languages from file names and extensions,
 * and filter reviewable code files.
 */
public final class LanguageDetector {

    private static final Map<String, String> EXTENSION_TO_LANGUAGE = new HashMap<>();

    static {
        // Java & JVM
        EXTENSION_TO_LANGUAGE.put("java", "Java");
        EXTENSION_TO_LANGUAGE.put("kt", "Kotlin");
        EXTENSION_TO_LANGUAGE.put("kts", "Kotlin");
        EXTENSION_TO_LANGUAGE.put("scala", "Scala");
        EXTENSION_TO_LANGUAGE.put("groovy", "Groovy");

        // .NET / C#
        EXTENSION_TO_LANGUAGE.put("cs", "C#");
        EXTENSION_TO_LANGUAGE.put("fs", "F#");
        EXTENSION_TO_LANGUAGE.put("vb", "Visual Basic");

        // Python
        EXTENSION_TO_LANGUAGE.put("py", "Python");
        EXTENSION_TO_LANGUAGE.put("pyw", "Python");

        // JavaScript & TypeScript
        EXTENSION_TO_LANGUAGE.put("js", "JavaScript");
        EXTENSION_TO_LANGUAGE.put("jsx", "JavaScript (React)");
        EXTENSION_TO_LANGUAGE.put("ts", "TypeScript");
        EXTENSION_TO_LANGUAGE.put("tsx", "TypeScript (React)");
        EXTENSION_TO_LANGUAGE.put("mjs", "JavaScript");
        EXTENSION_TO_LANGUAGE.put("cjs", "JavaScript");

        // Systems Programming
        EXTENSION_TO_LANGUAGE.put("go", "Go");
        EXTENSION_TO_LANGUAGE.put("rs", "Rust");
        EXTENSION_TO_LANGUAGE.put("c", "C");
        EXTENSION_TO_LANGUAGE.put("h", "C/C++ Header");
        EXTENSION_TO_LANGUAGE.put("cpp", "C++");
        EXTENSION_TO_LANGUAGE.put("cc", "C++");
        EXTENSION_TO_LANGUAGE.put("cxx", "C++");
        EXTENSION_TO_LANGUAGE.put("hpp", "C++ Header");

        // Mobile
        EXTENSION_TO_LANGUAGE.put("swift", "Swift");
        EXTENSION_TO_LANGUAGE.put("dart", "Dart");

        // Web & Scripting
        EXTENSION_TO_LANGUAGE.put("php", "PHP");
        EXTENSION_TO_LANGUAGE.put("rb", "Ruby");
        EXTENSION_TO_LANGUAGE.put("sql", "SQL");
        EXTENSION_TO_LANGUAGE.put("sh", "Shell/Bash");
        EXTENSION_TO_LANGUAGE.put("bash", "Shell/Bash");
        EXTENSION_TO_LANGUAGE.put("ps1", "PowerShell");
        EXTENSION_TO_LANGUAGE.put("lua", "Lua");
        EXTENSION_TO_LANGUAGE.put("r", "R");
    }

    private LanguageDetector() {
        // utility class
    }

    /**
     * Extracts the file extension from a file path.
     */
    public static String getExtension(String filePath) {
        if (filePath == null || filePath.isBlank()) return "";
        int lastDot = filePath.lastIndexOf('.');
        if (lastDot < 0 || lastDot == filePath.length() - 1) return "";
        return filePath.substring(lastDot + 1).toLowerCase();
    }

    /**
     * Detects the programming language for a given file name or path.
     */
    public static String detectLanguage(String filePath) {
        String ext = getExtension(filePath);
        return EXTENSION_TO_LANGUAGE.getOrDefault(ext, "General Code");
    }

    /**
     * Checks if a file is reviewable according to the configured language filter.
     *
     * @param filePath       the file path to check
     * @param filterLanguage "all", comma-separated languages, or specific language name/ext
     * @return true if the file should be reviewed
     */
    public static boolean isReviewable(String filePath, String filterLanguage) {
        if (filePath == null || filePath.isBlank()) return false;

        String ext = getExtension(filePath);
        if (ext.isEmpty()) return false;

        // Skip non-code files
        if (isIgnoredFile(filePath, ext)) return false;

        if (filterLanguage == null || filterLanguage.isBlank() || "all".equalsIgnoreCase(filterLanguage.trim())) {
            return EXTENSION_TO_LANGUAGE.containsKey(ext);
        }

        String[] allowed = filterLanguage.toLowerCase().split(",");
        String detectedLang = detectLanguage(filePath).toLowerCase();

        for (String item : allowed) {
            String clean = item.trim().toLowerCase();
            if (clean.equals(ext) || clean.equals(detectedLang) || normalizeAlias(clean).equals(detectedLang)) {
                return true;
            }
        }

        return false;
    }

    private static String normalizeAlias(String alias) {
        return switch (alias) {
            case "c#", "csharp", "dotnet" -> "c#";
            case "js", "node" -> "javascript";
            case "ts" -> "typescript";
            case "py" -> "python";
            case "golang" -> "go";
            case "c++" -> "c++";
            default -> alias;
        };
    }

    private static boolean isIgnoredFile(String filePath, String ext) {
        String lower = filePath.replace('\\', '/').toLowerCase();
        return lower.endsWith(".min.js")
                || lower.endsWith(".min.css")
                || lower.endsWith(".bundle.js")
                || lower.contains("package-lock.json")
                || lower.contains("yarn.lock")
                || lower.contains("pnpm-lock.yaml")
                || lower.contains(".generated.")
                || lower.contains("/bin/") || lower.startsWith("bin/")
                || lower.contains("/obj/") || lower.startsWith("obj/")
                || lower.contains("/target/") || lower.startsWith("target/")
                || lower.contains("/node_modules/") || lower.startsWith("node_modules/")
                || lower.contains("/vendor/") || lower.startsWith("vendor/");
    }
}
