package dev.codereviewer.llm;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codereviewer.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * LLM client for Google's Gemini API (AI Studio free tier).
 *
 * <p>Uses the REST endpoint directly via {@link java.net.http.HttpClient} —
 * no external HTTP library needed.
 *
 * <p>Key design decisions:
 * <ul>
 *   <li>Uses {@code responseMimeType: "application/json"} to force structured
 *       JSON output from Gemini, eliminating brittle text parsing</li>
 *   <li>Targets {@code gemini-2.5-flash} for its large context window (~1M tokens)
 *       and free-tier availability</li>
 *   <li>Passes the API key as a query parameter (AI Studio convention) rather
 *       than a header</li>
 * </ul>
 */
public class GeminiClient implements LlmClient {

    private static final Logger LOG = LoggerFactory.getLogger(GeminiClient.class);

    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String DEFAULT_MODEL = "gemini-2.0-flash";
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;

    public GeminiClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public GeminiClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String getName() {
        return "Gemini (" + model + ")";
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) throws LlmException {
        if (!isAvailable()) {
            throw new LlmException("Gemini API key not configured", false, -1);
        }

        String url = BASE_URL + model + ":generateContent?key=" + apiKey;

        // Build the Gemini request payload
        // Using system_instruction for the system prompt and contents for user message
        String requestBody = buildRequestBody(systemPrompt, userPrompt);

        LOG.debug("Sending request to Gemini ({}) — prompt size: {} chars", model, userPrompt.length());

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            return handleResponse(response);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Gemini API call failed: " + e.getMessage(), e, true);
        }
    }

    /**
     * Builds the Gemini API request JSON.
     */
    private String buildRequestBody(String systemPrompt, String userPrompt) {
        // Using raw JSON construction to avoid pulling in extra builder classes.
        // The structure follows Google's generativeai REST API format.
        return """
                {
                  "systemInstruction": {
                    "parts": [{"text": %s}]
                  },
                  "contents": [{
                    "role": "user",
                    "parts": [{"text": %s}]
                  }],
                  "generationConfig": {
                    "responseMimeType": "application/json",
                    "temperature": 0.3,
                    "maxOutputTokens": 8192
                  }
                }
                """.formatted(
                JsonUtil.toJson(systemPrompt),
                JsonUtil.toJson(userPrompt)
        );
    }

    /**
     * Parses the Gemini API response and extracts the generated text.
     */
    private String handleResponse(HttpResponse<String> response) throws LlmException {
        int status = response.statusCode();
        String body = response.body();

        if (status == 429) {
            LOG.warn("Gemini rate limit hit (429)");
            throw new LlmException("Rate limit exceeded", true, 429);
        }

        if (status >= 500) {
            LOG.warn("Gemini server error ({}): {}", status, truncate(body, 200));
            throw new LlmException("Server error: " + status, true, status);
        }

        if (status != 200) {
            LOG.error("Gemini error ({}): {}", status, truncate(body, 500));
            throw new LlmException("API error: " + status + " — " + truncate(body, 200), false, status);
        }

        // Parse the response JSON to extract the text
        try {
            JsonNode root = JsonUtil.mapper().readTree(body);
            JsonNode candidates = root.get("candidates");

            if (candidates == null || candidates.isEmpty()) {
                // Check for prompt feedback (blocked content)
                JsonNode feedback = root.get("promptFeedback");
                if (feedback != null) {
                    String reason = feedback.has("blockReason")
                            ? feedback.get("blockReason").asText()
                            : "unknown";
                    throw new LlmException("Prompt blocked by Gemini: " + reason, false, 200);
                }
                throw new LlmException("No candidates in Gemini response", false, 200);
            }

            JsonNode content = candidates.get(0).get("content");
            if (content == null || !content.has("parts")) {
                throw new LlmException("Malformed Gemini response: missing content.parts", false, 200);
            }

            String text = content.get("parts").get(0).get("text").asText();
            LOG.debug("Gemini response received — {} chars", text.length());
            return text;

        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Failed to parse Gemini response: " + e.getMessage(), e, false);
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
