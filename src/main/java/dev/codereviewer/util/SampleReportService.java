package dev.codereviewer.util;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;

/**
 * Sample report service to demonstrate AI code review capabilities.
 */
public class SampleReportService {

    /**
     * Reads a report file and formats content into a summary string.
     */
    public String generateReportSummary(String filePath, List<String> tags) {
        String result = "";

        // Anti-pattern 1: Unclosed resource (should use try-with-resources)
        try {
            BufferedReader reader = new BufferedReader(new FileReader(filePath));
            String line;
            while ((line = reader.readLine()) != null) {
                // Anti-pattern 2: String concatenation in loop (should use StringBuilder)
                result += line + "\n";
            }
        } catch (Exception e) {
            // Anti-pattern 3: Swallowing exception without logging
        }

        // Anti-pattern 4: Potential NullPointerException
        if (tags != null && tags.size() > 0) {
            for (String tag : tags) {
                result += " #" + tag.toUpperCase();
            }
        } else {
            // Null dereference risk if tags is null
            System.out.println("No tags provided for: " + tags.toString());
        }

        return result;
    }
}
