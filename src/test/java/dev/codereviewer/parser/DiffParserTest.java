package dev.codereviewer.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DiffParser}.
 */
class DiffParserTest {

    @Test
    @DisplayName("Parses a single hunk with additions and deletions")
    void parseSingleHunk() {
        // Using string concatenation to precisely control leading characters in diff lines
        String patch = "@@ -10,6 +10,8 @@ public class UserService {\n"
                + "     private final UserRepository repo;\n"
                + "\n"
                + "-    public User findById(long id) {\n"
                + "-        return repo.findById(id);\n"
                + "+    public Optional<User> findById(long id) {\n"
                + "+        return Optional.ofNullable(repo.findById(id));\n"
                + "+    }\n"
                + "+\n"
                + "     public void deleteUser(long id) {\n";

        List<DiffParser.DiffHunk> hunks = DiffParser.parse(patch);

        assertEquals(1, hunks.size());

        DiffParser.DiffHunk hunk = hunks.get(0);
        assertEquals(10, hunk.oldStart());
        assertEquals(6, hunk.oldCount());
        assertEquals(10, hunk.newStart());
        assertEquals(8, hunk.newCount());
        assertEquals("public class UserService {", hunk.headerContext());

        // Verify line categorization
        long addCount = hunk.lines().stream()
                .filter(l -> l.type() == DiffParser.DiffLine.Type.ADD).count();
        long deleteCount = hunk.lines().stream()
                .filter(l -> l.type() == DiffParser.DiffLine.Type.DELETE).count();
        long contextCount = hunk.lines().stream()
                .filter(l -> l.type() == DiffParser.DiffLine.Type.CONTEXT).count();

        assertEquals(4, addCount);
        assertEquals(2, deleteCount);
        assertEquals(2, contextCount); // 2 context lines with leading space (blank line skipped)
    }

    @Test
    @DisplayName("Parses multiple hunks")
    void parseMultipleHunks() {
        String patch = """
                @@ -5,3 +5,4 @@ import java.util.List;
                 import java.util.Map;
                +import java.util.Optional;
                 
                @@ -20,4 +21,5 @@ public class Service {
                     void process() {
                +        // TODO: implement
                     }
                """;

        List<DiffParser.DiffHunk> hunks = DiffParser.parse(patch);
        assertEquals(2, hunks.size());

        assertEquals(5, hunks.get(0).oldStart());
        assertEquals(20, hunks.get(1).oldStart());
    }

    @Test
    @DisplayName("Returns empty list for null/blank patch")
    void parseNullPatch() {
        assertTrue(DiffParser.parse(null).isEmpty());
        assertTrue(DiffParser.parse("").isEmpty());
        assertTrue(DiffParser.parse("   ").isEmpty());
    }

    @Test
    @DisplayName("getAddedLineNumbers returns only ADD lines")
    void addedLineNumbers() {
        String patch = """
                @@ -1,3 +1,4 @@
                 existing line
                +new line 1
                +new line 2
                 another existing
                """;

        List<DiffParser.DiffHunk> hunks = DiffParser.parse(patch);
        List<Integer> added = DiffParser.getAddedLineNumbers(hunks);

        assertEquals(2, added.size());
        assertTrue(added.contains(2));
        assertTrue(added.contains(3));
    }

    @Test
    @DisplayName("isLineInDiff correctly identifies lines in/out of diff")
    void lineInDiff() {
        // Use string concatenation for precise control of diff line prefixes
        String patch = "@@ -10,3 +10,4 @@\n"
                + " context\n"
                + "+added\n"
                + " context2\n";

        List<DiffParser.DiffHunk> hunks = DiffParser.parse(patch);

        assertTrue(DiffParser.isLineInDiff(hunks, 10));  // context line
        assertTrue(DiffParser.isLineInDiff(hunks, 11));  // added line
        assertTrue(DiffParser.isLineInDiff(hunks, 12));  // context line
        assertFalse(DiffParser.isLineInDiff(hunks, 9));  // before hunk
        assertFalse(DiffParser.isLineInDiff(hunks, 13)); // after hunk
    }

    @Test
    @DisplayName("Handles hunk header without count (defaults to 1)")
    void hunkWithoutCount() {
        String patch = "@@ -1 +1 @@\n-old\n+new\n";

        List<DiffParser.DiffHunk> hunks = DiffParser.parse(patch);
        assertEquals(1, hunks.size());
        assertEquals(1, hunks.get(0).oldCount());
        assertEquals(1, hunks.get(0).newCount());
    }
}
