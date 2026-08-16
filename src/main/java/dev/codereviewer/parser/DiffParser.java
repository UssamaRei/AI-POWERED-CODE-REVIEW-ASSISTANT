package dev.codereviewer.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses unified diff format (as returned by GitHub's PR API) into structured
 * {@link DiffHunk} objects.
 *
 * <p>A unified diff looks like:
 * <pre>
 * @@ -10,5 +10,7 @@ public class Foo {
 *  unchanged line
 * -removed line
 * +added line
 *  unchanged line
 * </pre>
 *
 * <p>This parser extracts hunks with their line ranges and categorized lines
 * (ADD, DELETE, CONTEXT), which are needed for:
 * <ul>
 *   <li>Mapping AST node positions to diff lines</li>
 *   <li>Determining which methods were actually changed</li>
 *   <li>Validating that review comments target lines in the diff</li>
 * </ul>
 */
public final class DiffParser {

    private static final Logger LOG = LoggerFactory.getLogger(DiffParser.class);

    /**
     * Regex for the unified diff hunk header.
     * Matches: @@ -oldStart,oldCount +newStart,newCount @@ optional context
     * The count part is optional (defaults to 1 if absent).
     */
    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^@@\\s+-(?:(\\d+))(?:,(\\d+))?\\s+\\+(?:(\\d+))(?:,(\\d+))?\\s+@@(.*)$"
    );

    private DiffParser() {
        // utility class
    }

    /**
     * Parses a unified diff patch string into a list of DiffHunks.
     *
     * @param patch the raw patch string from GitHub (may be null for binary files)
     * @return list of parsed hunks, empty if patch is null/empty
     */
    public static List<DiffHunk> parse(String patch) {
        if (patch == null || patch.isBlank()) {
            return List.of();
        }

        List<DiffHunk> hunks = new ArrayList<>();
        String[] lines = patch.split("\n");

        DiffHunk currentHunk = null;
        int currentOldLine = 0;
        int currentNewLine = 0;

        for (String line : lines) {
            Matcher headerMatch = HUNK_HEADER.matcher(line);

            if (headerMatch.matches()) {
                // Save previous hunk
                if (currentHunk != null) {
                    hunks.add(currentHunk);
                }

                int oldStart = Integer.parseInt(headerMatch.group(1));
                int oldCount = headerMatch.group(2) != null ? Integer.parseInt(headerMatch.group(2)) : 1;
                int newStart = Integer.parseInt(headerMatch.group(3));
                int newCount = headerMatch.group(4) != null ? Integer.parseInt(headerMatch.group(4)) : 1;
                String context = headerMatch.group(5) != null ? headerMatch.group(5).trim() : "";

                currentHunk = new DiffHunk(oldStart, oldCount, newStart, newCount, context, new ArrayList<>());
                currentOldLine = oldStart;
                currentNewLine = newStart;
                continue;
            }

            if (currentHunk == null) {
                // Lines before the first hunk header (e.g., file header) — skip
                continue;
            }

            // Skip completely empty lines (trailing newlines, blank separators)
            if (line.isEmpty()) {
                continue;
            }

            if (line.startsWith("+")) {
                currentHunk.lines().add(new DiffLine(
                        DiffLine.Type.ADD,
                        line.substring(1),
                        -1,
                        currentNewLine
                ));
                currentNewLine++;
            } else if (line.startsWith("-")) {
                currentHunk.lines().add(new DiffLine(
                        DiffLine.Type.DELETE,
                        line.substring(1),
                        currentOldLine,
                        -1
                ));
                currentOldLine++;
            } else if (line.startsWith("\\")) {
                // "\ No newline at end of file" — metadata, skip
                continue;
            } else {
                // Context line (starts with space or is empty)
                String content = line.startsWith(" ") ? line.substring(1) : line;
                currentHunk.lines().add(new DiffLine(
                        DiffLine.Type.CONTEXT,
                        content,
                        currentOldLine,
                        currentNewLine
                ));
                currentOldLine++;
                currentNewLine++;
            }
        }

        // Don't forget the last hunk
        if (currentHunk != null) {
            hunks.add(currentHunk);
        }

        LOG.debug("Parsed {} hunk(s) from patch ({} lines)", hunks.size(), lines.length);
        return hunks;
    }

    /**
     * Returns the set of new-file line numbers that are additions (not context/deletes).
     * These are the only lines where GitHub allows inline comments.
     */
    public static List<Integer> getAddedLineNumbers(List<DiffHunk> hunks) {
        List<Integer> addedLines = new ArrayList<>();
        for (DiffHunk hunk : hunks) {
            for (DiffLine line : hunk.lines()) {
                if (line.type() == DiffLine.Type.ADD && line.newLineNumber() > 0) {
                    addedLines.add(line.newLineNumber());
                }
            }
        }
        return addedLines;
    }

    /**
     * Returns all new-file line numbers present in the diff (both added and context).
     * These represent all lines that could potentially receive a review comment.
     */
    public static List<Integer> getAllDiffLineNumbers(List<DiffHunk> hunks) {
        List<Integer> diffLines = new ArrayList<>();
        for (DiffHunk hunk : hunks) {
            for (DiffLine line : hunk.lines()) {
                if (line.newLineNumber() > 0) {
                    diffLines.add(line.newLineNumber());
                }
            }
        }
        return diffLines;
    }

    /**
     * Checks whether a given new-file line number falls within any diff hunk.
     */
    public static boolean isLineInDiff(List<DiffHunk> hunks, int newLineNumber) {
        for (DiffHunk hunk : hunks) {
            for (DiffLine line : hunk.lines()) {
                if (line.newLineNumber() == newLineNumber) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Represents a single hunk in a unified diff.
     */
    public record DiffHunk(
            int oldStart,
            int oldCount,
            int newStart,
            int newCount,
            String headerContext,   // the text after @@ (e.g., method name)
            List<DiffLine> lines
    ) {
        /**
         * Returns the range of new-file line numbers covered by this hunk.
         */
        public int newEnd() {
            return newStart + newCount - 1;
        }
    }

    /**
     * Represents a single line within a diff hunk.
     */
    public record DiffLine(
            Type type,
            String content,
            int oldLineNumber,   // -1 for ADD lines
            int newLineNumber    // -1 for DELETE lines
    ) {
        public enum Type {
            ADD,        // line was added (starts with +)
            DELETE,     // line was removed (starts with -)
            CONTEXT     // unchanged line for context (starts with space)
        }
    }
}
