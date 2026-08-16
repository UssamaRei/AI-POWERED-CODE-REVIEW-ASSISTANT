package dev.codereviewer.parser;

import java.util.List;

/**
 * Holds the structural analysis results for a single changed file.
 *
 * <p>Combines AST-derived information (class structure, method signatures)
 * with diff information (which hunks changed, which methods were affected).
 * This rich context is what makes the review "structural" rather than
 * just line-by-line text matching.
 */
public record CodeContext(
        /** Path relative to the repository root */
        String filePath,

        /** Java package name (e.g., "com.example.service") */
        String packageName,

        /** Simple class name (e.g., "UserService") */
        String className,

        /** Fully qualified import statements */
        List<String> imports,

        /** All method signatures in the class */
        List<MethodSignature> methods,

        /** Methods whose bodies overlap with diff hunks (the actually changed methods) */
        List<MethodSignature> changedMethods,

        /** Field declarations */
        List<FieldInfo> fields,

        /** Superclass fully qualified name, or null */
        String superClass,

        /** Implemented interface names */
        List<String> interfaces,

        /** Raw unified diff patch */
        String rawDiff,

        /** Parsed diff hunks */
        List<DiffParser.DiffHunk> hunks,

        /** Whether the AST parse succeeded (false = fell back to raw diff) */
        boolean parseSucceeded
) {

    /**
     * Represents a method signature extracted from the AST.
     */
    public record MethodSignature(
            String name,
            String returnType,
            List<String> parameterTypes,
            List<String> annotations,
            int startLine,
            int endLine,
            String body  // full method source, may be null for API-only context
    ) {
        /**
         * Returns a concise signature string like "void processOrder(String, int)".
         */
        public String toSignatureString() {
            return returnType + " " + name + "(" + String.join(", ", parameterTypes) + ")";
        }
    }

    /**
     * Represents a field declaration extracted from the AST.
     */
    public record FieldInfo(
            String name,
            String type,
            List<String> annotations,
            int line
    ) {}

    /**
     * Creates a fallback CodeContext when AST parsing fails.
     * Contains only the diff information — no structural data.
     */
    public static CodeContext fallback(String filePath, String rawDiff, List<DiffParser.DiffHunk> hunks) {
        return new CodeContext(
                filePath,
                null,           // packageName
                null,           // className
                List.of(),      // imports
                List.of(),      // methods
                List.of(),      // changedMethods
                List.of(),      // fields
                null,           // superClass
                List.of(),      // interfaces
                rawDiff,
                hunks,
                false           // parseSucceeded
        );
    }
}
