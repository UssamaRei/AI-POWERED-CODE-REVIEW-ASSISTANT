package dev.codereviewer.context;

import dev.codereviewer.github.PullRequestService;
import dev.codereviewer.parser.AstAnalyzer;
import dev.codereviewer.parser.CodeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gathers related code context for a changed file so the LLM doesn't review
 * the diff in isolation.
 *
 * <p>Context enrichment strategy (heuristic, Phase 1 — replaces the need
 * for a full Lucene vector store):
 * <ol>
 *   <li><b>Import following:</b> For imports referencing in-repo classes, fetch
 *       their public API (method signatures, class Javadoc)</li>
 *   <li><b>Caller detection:</b> If other changed files in the same PR reference
 *       the current file's class, include their relevant snippets</li>
 *   <li><b>Superclass/interface contracts:</b> If the changed class extends or
 *       implements an in-repo type, pull the parent's method signatures</li>
 * </ol>
 *
 * <p>This produces a "related context" string that gets appended to the
 * LLM prompt, giving it cross-file awareness without a full embedding pipeline.
 */
public class ContextBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(ContextBuilder.class);

    /**
     * Maximum number of related files to include context from.
     * Prevents the prompt from exploding on large dependency trees.
     */
    private static final int MAX_RELATED_FILES = 5;

    private final PullRequestService prService;
    private final AstAnalyzer astAnalyzer;
    private final String headSha;

    /**
     * All changed files in this PR (set of filenames), used for caller detection.
     */
    private final Set<String> changedFileNames;

    /**
     * Cache of already-fetched contexts to avoid redundant API calls.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, CodeContext> contextCache
            = new java.util.concurrent.ConcurrentHashMap<>();

    public ContextBuilder(PullRequestService prService, AstAnalyzer astAnalyzer,
                          String headSha, List<PullRequestService.ChangedFile> allChangedFiles) {
        this.prService = prService;
        this.astAnalyzer = astAnalyzer;
        this.headSha = headSha;
        this.changedFileNames = allChangedFiles.stream()
                .map(PullRequestService.ChangedFile::filename)
                .collect(Collectors.toSet());
    }

    /**
     * Builds a related context string for the given file's CodeContext.
     *
     * @param fileContext the CodeContext of the file being reviewed
     * @return markdown-formatted context string, or empty string if no relevant context found
     */
    public String buildRelatedContext(CodeContext fileContext) {
        if (!fileContext.parseSucceeded()) {
            return ""; // Can't follow imports/hierarchy without AST
        }

        List<RelatedSnippet> snippets = new ArrayList<>();

        // 1. Follow imports to find in-repo dependencies
        gatherImportContext(fileContext, snippets);

        // 2. Follow superclass/interface hierarchy
        gatherHierarchyContext(fileContext, snippets);

        // Limit total snippets
        if (snippets.size() > MAX_RELATED_FILES) {
            snippets = snippets.subList(0, MAX_RELATED_FILES);
        }

        if (snippets.isEmpty()) {
            return "";
        }

        // Format as markdown
        StringBuilder sb = new StringBuilder();
        for (RelatedSnippet snippet : snippets) {
            sb.append("**`").append(snippet.filePath).append("`** (").append(snippet.relevance).append(")\n");
            sb.append("```java\n").append(snippet.content).append("\n```\n\n");
        }

        LOG.debug("Built related context for {}: {} snippets", fileContext.filePath(), snippets.size());
        return sb.toString();
    }

    /**
     * Checks imports for in-repo files and fetches their API signatures.
     */
    private void gatherImportContext(CodeContext fileContext, List<RelatedSnippet> snippets) {
        for (String importName : fileContext.imports()) {
            // Skip JDK and common library imports
            if (isExternalImport(importName)) {
                continue;
            }

            // Convert import to a likely file path
            String candidatePath = importToPath(importName);
            if (candidatePath == null) continue;

            // Check if this file exists in the repo
            String source = prService.getFileContent(candidatePath, headSha);
            if (source == null) continue;

            // Extract just the public API (method signatures, no bodies)
            String apiSummary = extractApiSummary(source, candidatePath);
            if (apiSummary != null && !apiSummary.isBlank()) {
                snippets.add(new RelatedSnippet(candidatePath, "imported dependency", apiSummary));
            }

            if (snippets.size() >= MAX_RELATED_FILES) break;
        }
    }

    /**
     * Fetches the superclass/interface API if they're in-repo.
     */
    private void gatherHierarchyContext(CodeContext fileContext, List<RelatedSnippet> snippets) {
        // Check superclass
        if (fileContext.superClass() != null) {
            String superPath = findFileForClass(fileContext.superClass(), fileContext.imports());
            if (superPath != null) {
                String source = prService.getFileContent(superPath, headSha);
                if (source != null) {
                    String apiSummary = extractApiSummary(source, superPath);
                    if (apiSummary != null) {
                        snippets.add(new RelatedSnippet(superPath, "superclass contract", apiSummary));
                    }
                }
            }
        }

        // Check interfaces
        for (String iface : fileContext.interfaces()) {
            if (snippets.size() >= MAX_RELATED_FILES) break;

            String ifacePath = findFileForClass(iface, fileContext.imports());
            if (ifacePath != null) {
                String source = prService.getFileContent(ifacePath, headSha);
                if (source != null) {
                    String apiSummary = extractApiSummary(source, ifacePath);
                    if (apiSummary != null) {
                        snippets.add(new RelatedSnippet(ifacePath, "interface contract", apiSummary));
                    }
                }
            }
        }
    }

    /**
     * Extracts a concise API summary (method signatures only) from source code.
     */
    private String extractApiSummary(String sourceCode, String filePath) {
        CodeContext context = contextCache.computeIfAbsent(filePath,
                path -> astAnalyzer.analyze(sourceCode, path, null, List.of()));

        if (!context.parseSucceeded() || context.methods().isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        if (context.className() != null) {
            sb.append("// ").append(context.className());
            if (context.superClass() != null) sb.append(" extends ").append(context.superClass());
            if (!context.interfaces().isEmpty())
                sb.append(" implements ").append(String.join(", ", context.interfaces()));
            sb.append("\n");
        }

        for (CodeContext.MethodSignature method : context.methods()) {
            String annotations = method.annotations().isEmpty() ? ""
                    : method.annotations().stream().map(a -> "@" + a).collect(Collectors.joining(" ")) + " ";
            sb.append(annotations)
                    .append(method.toSignatureString())
                    .append(";\n");
        }

        return sb.toString();
    }

    /**
     * Determines if an import is from an external library (JDK, common libs).
     */
    private boolean isExternalImport(String importName) {
        return importName.startsWith("java.")
                || importName.startsWith("javax.")
                || importName.startsWith("jakarta.")
                || importName.startsWith("org.slf4j")
                || importName.startsWith("org.junit")
                || importName.startsWith("org.mockito")
                || importName.startsWith("com.fasterxml")
                || importName.startsWith("com.github.javaparser")
                || importName.startsWith("org.kohsuke")
                || importName.startsWith("ch.qos")
                || importName.startsWith("org.apache");
    }

    /**
     * Converts a fully-qualified import name to a likely source file path.
     * e.g., "com.example.service.UserService" → "src/main/java/com/example/service/UserService.java"
     */
    private String importToPath(String importName) {
        if (importName.endsWith(".*")) {
            return null; // Wildcard imports can't be resolved to a single file
        }
        String path = importName.replace('.', '/');
        return "src/main/java/" + path + ".java";
    }

    /**
     * Tries to find the source file for a simple class name by matching against imports.
     */
    private String findFileForClass(String simpleName, List<String> imports) {
        for (String imp : imports) {
            if (imp.endsWith("." + simpleName)) {
                return importToPath(imp);
            }
        }
        return null;
    }

    /**
     * A snippet of related code with its source file and relevance reason.
     */
    private record RelatedSnippet(String filePath, String relevance, String content) {}
}
