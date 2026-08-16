package dev.codereviewer.parser;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parses Java source files into rich structural representations using JavaParser.
 *
 * <p>This is the core "code understanding" component — it provides the AST-level
 * insight that distinguishes this tool from a simple text-diff reviewer:
 * <ul>
 *   <li>Identifies which methods were actually changed (not just which lines)</li>
 *   <li>Extracts class hierarchy (extends/implements) for contract checking</li>
 *   <li>Lists imports for dependency/context gathering</li>
 *   <li>Optionally resolves symbols across files via JavaParser's symbol solver</li>
 * </ul>
 *
 * <p>Gracefully falls back to a raw-diff-only {@link CodeContext} if parsing fails
 * (e.g., the PR introduces a syntax error).
 */
public class AstAnalyzer {

    private static final Logger LOG = LoggerFactory.getLogger(AstAnalyzer.class);

    private final ParserConfiguration parserConfig;
    private final boolean symbolResolutionEnabled;

    /**
     * Creates an AstAnalyzer with optional symbol resolution.
     *
     * @param workspacePath path to the repo root (used for symbol resolution)
     */
    public AstAnalyzer(String workspacePath) {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver()); // JDK types

        boolean canResolve = false;

        // Add the workspace source directories for cross-file resolution
        if (workspacePath != null) {
            Path srcMain = Path.of(workspacePath, "src", "main", "java");
            if (srcMain.toFile().exists()) {
                typeSolver.add(new JavaParserTypeSolver(srcMain.toFile()));
                canResolve = true;
            }

            // Also check if the workspace root itself contains Java files (flat layout)
            Path root = Path.of(workspacePath);
            if (!canResolve && root.toFile().exists()) {
                typeSolver.add(new JavaParserTypeSolver(root.toFile()));
                canResolve = true;
            }
        }

        this.symbolResolutionEnabled = canResolve;

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        this.parserConfig = new ParserConfiguration()
                .setSymbolResolver(symbolSolver)
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

        LOG.info("AstAnalyzer initialized (symbol resolution: {})", canResolve ? "enabled" : "disabled");
    }

    /**
     * Analyzes a Java source file and returns a rich CodeContext.
     *
     * @param sourceCode  the full Java source code at HEAD
     * @param filePath    the file path (for error reporting)
     * @param patch       the raw diff patch
     * @param hunks       pre-parsed diff hunks
     * @return CodeContext with structural analysis, or a fallback if parsing fails
     */
    public CodeContext analyze(String sourceCode, String filePath, String patch,
                               List<DiffParser.DiffHunk> hunks) {
        if (sourceCode == null || sourceCode.isBlank()) {
            LOG.warn("No source code for {}, using fallback", filePath);
            return CodeContext.fallback(filePath, patch, hunks);
        }

        try {
            StaticJavaParser.setConfiguration(parserConfig);
            CompilationUnit cu = StaticJavaParser.parse(sourceCode);

            return buildCodeContext(cu, filePath, patch, hunks, sourceCode);
        } catch (Exception e) {
            LOG.warn("AST parse failed for {} ({}), falling back to raw diff", filePath, e.getMessage());
            return CodeContext.fallback(filePath, patch, hunks);
        }
    }

    /**
     * Builds a CodeContext from a successfully parsed CompilationUnit.
     */
    private CodeContext buildCodeContext(CompilationUnit cu, String filePath,
                                         String patch, List<DiffParser.DiffHunk> hunks,
                                         String sourceCode) {
        // Package
        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse(null);

        // Imports
        List<String> imports = cu.getImports().stream()
                .map(ImportDeclaration::getNameAsString)
                .toList();

        // Find the primary type declaration
        Optional<TypeDeclaration<?>> primaryType = cu.getTypes().stream()
                .filter(t -> t instanceof ClassOrInterfaceDeclaration)
                .findFirst()
                .map(t -> (TypeDeclaration<?>) t);

        String className = primaryType
                .map(TypeDeclaration::getNameAsString)
                .orElse(null);

        // Superclass and interfaces
        String superClass = null;
        List<String> interfaces = new ArrayList<>();

        if (primaryType.isPresent() && primaryType.get() instanceof ClassOrInterfaceDeclaration cid) {
            if (!cid.getExtendedTypes().isEmpty()) {
                superClass = cid.getExtendedTypes(0).getNameAsString();
            }
            interfaces = cid.getImplementedTypes().stream()
                    .map(t -> t.getNameAsString())
                    .toList();
        }

        // Methods
        List<CodeContext.MethodSignature> methods = new ArrayList<>();
        String[] sourceLines = sourceCode.split("\n", -1);

        cu.findAll(MethodDeclaration.class).forEach(md -> {
            methods.add(extractMethodSignature(md, sourceLines));
        });

        // Also include constructors
        cu.findAll(ConstructorDeclaration.class).forEach(cd -> {
            methods.add(extractConstructorSignature(cd, sourceLines));
        });

        // Fields
        List<CodeContext.FieldInfo> fields = new ArrayList<>();
        cu.findAll(FieldDeclaration.class).forEach(fd -> {
            fd.getVariables().forEach(v -> {
                fields.add(new CodeContext.FieldInfo(
                        v.getNameAsString(),
                        fd.getElementType().asString(),
                        extractAnnotations(fd),
                        fd.getBegin().map(p -> p.line).orElse(-1)
                ));
            });
        });

        // Determine which methods were changed by cross-referencing with diff hunks
        List<CodeContext.MethodSignature> changedMethods = methods.stream()
                .filter(m -> isMethodInDiff(m, hunks))
                .toList();

        LOG.debug("Analyzed {}: {} methods ({} changed), {} fields, {} imports",
                filePath, methods.size(), changedMethods.size(), fields.size(), imports.size());

        return new CodeContext(
                filePath, packageName, className, imports,
                methods, changedMethods, fields,
                superClass, interfaces,
                patch, hunks, true
        );
    }

    /**
     * Extracts a MethodSignature from a MethodDeclaration AST node.
     */
    private CodeContext.MethodSignature extractMethodSignature(MethodDeclaration md, String[] sourceLines) {
        List<String> paramTypes = md.getParameters().stream()
                .map(p -> p.getType().asString())
                .toList();

        int startLine = md.getBegin().map(p -> p.line).orElse(-1);
        int endLine = md.getEnd().map(p -> p.line).orElse(-1);

        // Extract the method body source
        String body = null;
        if (startLine > 0 && endLine > 0 && endLine <= sourceLines.length) {
            StringBuilder sb = new StringBuilder();
            for (int i = startLine - 1; i < endLine; i++) {
                sb.append(sourceLines[i]).append("\n");
            }
            body = sb.toString();
        }

        return new CodeContext.MethodSignature(
                md.getNameAsString(),
                md.getType().asString(),
                paramTypes,
                extractAnnotations(md),
                startLine,
                endLine,
                body
        );
    }

    /**
     * Extracts a MethodSignature from a ConstructorDeclaration AST node.
     */
    private CodeContext.MethodSignature extractConstructorSignature(ConstructorDeclaration cd, String[] sourceLines) {
        List<String> paramTypes = cd.getParameters().stream()
                .map(p -> p.getType().asString())
                .toList();

        int startLine = cd.getBegin().map(p -> p.line).orElse(-1);
        int endLine = cd.getEnd().map(p -> p.line).orElse(-1);

        String body = null;
        if (startLine > 0 && endLine > 0 && endLine <= sourceLines.length) {
            StringBuilder sb = new StringBuilder();
            for (int i = startLine - 1; i < endLine; i++) {
                sb.append(sourceLines[i]).append("\n");
            }
            body = sb.toString();
        }

        return new CodeContext.MethodSignature(
                cd.getNameAsString(),
                "<constructor>",
                paramTypes,
                extractAnnotations(cd),
                startLine,
                endLine,
                body
        );
    }

    /**
     * Extracts annotation names from a node.
     */
    private List<String> extractAnnotations(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .map(a -> a.getNameAsString())
                .toList();
    }

    /**
     * Checks whether a method's line range overlaps with any diff hunk.
     */
    private boolean isMethodInDiff(CodeContext.MethodSignature method, List<DiffParser.DiffHunk> hunks) {
        if (method.startLine() <= 0 || method.endLine() <= 0) {
            return false;
        }

        for (DiffParser.DiffHunk hunk : hunks) {
            int hunkNewEnd = hunk.newStart() + hunk.newCount() - 1;
            // Check for overlap between [method.startLine, method.endLine] and [hunk.newStart, hunkNewEnd]
            if (method.startLine() <= hunkNewEnd && method.endLine() >= hunk.newStart()) {
                return true;
            }
        }
        return false;
    }
}
