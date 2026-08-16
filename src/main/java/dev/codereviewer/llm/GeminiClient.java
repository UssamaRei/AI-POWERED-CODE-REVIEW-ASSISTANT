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
import java.util.List;

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

    private static final List<String> ENDPOINT_CANDIDATES = List.of(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash",
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest",
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite",
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.7-flash",
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro"
    );
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    private final String apiKey;
    private final String customModel;
    private final HttpClient httpClient;
    private volatile String workingEndpointBase;

    public GeminiClient(String apiKey) {
        this(apiKey, null);
    }

    public GeminiClient(String apiKey, String customModel) {
        this.apiKey = apiKey != null ? apiKey.trim() : null;
        this.customModel = customModel;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String getName() {
        return "Gemini (" + (workingEndpointBase != null ? workingEndpointBase.substring(workingEndpointBase.lastIndexOf('/') + 1) : (customModel != null ? customModel : "auto")) + ")";
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

        String requestBody = buildRequestBody(systemPrompt, userPrompt);

        // If a custom model was given, use it directly
        if (customModel != null) {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + customModel + ":generateContent?key=" + apiKey;
            return sendRequest(url, requestBody);
        }

        // If a previously working endpoint exists, try it first
        if (workingEndpointBase != null) {
            String url = workingEndpointBase + ":generateContent?key=" + apiKey;
            try {
                return sendRequest(url, requestBody);
            } catch (LlmException e) {
                if (e.getHttpStatus() == 503 || e.getHttpStatus() == 429 || e.getHttpStatus() >= 500) {
                    LOG.warn("Cached endpoint {} returned {}, trying other candidates...", workingEndpointBase, e.getHttpStatus());
                    workingEndpointBase = null;
                } else {
                    throw e;
                }
            }
        }

        // Try candidate endpoints until one succeeds
        LlmException lastException = null;
        for (String candidate : ENDPOINT_CANDIDATES) {
            String url = candidate + ":generateContent?key=" + apiKey;
            LOG.info("Trying Gemini endpoint: {}", candidate);

            try {
                String response = sendRequest(url, requestBody);
                workingEndpointBase = candidate;
                LOG.info("Successfully connected using Gemini endpoint: {}", candidate);
                return response;
            } catch (LlmException e) {
                lastException = e;
                int status = e.getHttpStatus();
                if (status == 404 || status == 503 || status == 429 || status >= 500) {
                    LOG.warn("Endpoint {} returned {}, trying next candidate...", candidate, status);
                    continue;
                }
                // For client errors (400, 401, 403), fail fast
                throw e;
            }
        }

        if (lastException != null) {
            throw lastException;
        }
        throw new LlmException("All Gemini model endpoints failed", false, -1);
    }

    private String sendRequest(String url, String requestBody) throws LlmException {
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
            LOG.error("Gemini request exception: {}", e.getMessage(), e);
            throw new LlmException("Gemini API call failed: " + e.getMessage(), e, true);
        }
    }

    /**
     * Builds the Gemini API request JSON using Jackson.
     */
    private String buildRequestBody(String systemPrompt, String userPrompt) {
        com.fasterxml.jackson.databind.node.ObjectNode root = JsonUtil.mapper().createObjectNode();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            com.fasterxml.jackson.databind.node.ObjectNode sysInstruction = root.putObject("systemInstruction");
            com.fasterxml.jackson.databind.node.ArrayNode sysParts = sysInstruction.putArray("parts");
            sysParts.addObject().put("text", systemPrompt);
        }

        com.fasterxml.jackson.databind.node.ArrayNode contents = root.putArray("contents");
        com.fasterxml.jackson.databind.node.ObjectNode userContent = contents.addObject();
        userContent.put("role", "user");
        com.fasterxml.jackson.databind.node.ArrayNode userParts = userContent.putArray("parts");
        userParts.addObject().put("text", userPrompt);

        com.fasterxml.jackson.databind.node.ObjectNode genConfig = root.putObject("generationConfig");
        genConfig.put("responseMimeType", "application/json");
        genConfig.put("temperature", 0.3);
        genConfig.put("maxOutputTokens", 8192);

        return JsonUtil.toJson(root);
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
            LOG.error("Gemini error ({}): {}", status, body);
            throw new LlmException("API error " + status + ": " + truncate(body, 200), false, status);
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
