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
 * LLM client for Groq's API (free tier), used as a fallback when Gemini
 * is rate-limited or unavailable.
 *
 * <p>Groq's API follows the OpenAI chat completions format, making it a
 * straightforward drop-in alternative. Key differences from Gemini:
 * <ul>
 *   <li>Auth via Bearer token header (not query param)</li>
 *   <li>Messages array with role-based format (system/user/assistant)</li>
 *   <li>Uses {@code response_format: {"type": "json_object"}} for structured output</li>
 *   <li>Smaller context windows than Gemini Flash — prompt chunking matters more here</li>
 * </ul>
 */
public class GroqClient implements LlmClient {

    private static final Logger LOG = LoggerFactory.getLogger(GroqClient.class);

    private static final String BASE_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String DEFAULT_MODEL = "llama-3.3-70b-versatile";
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;

    public GroqClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public GroqClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String getName() {
        return "Groq (" + model + ")";
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) throws LlmException {
        if (!isAvailable()) {
            throw new LlmException("Groq API key not configured", false, -1);
        }

        String requestBody = buildRequestBody(systemPrompt, userPrompt);

        LOG.debug("Sending request to Groq ({}) — prompt size: {} chars", model, userPrompt.length());

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            return handleResponse(response);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Groq API call failed: " + e.getMessage(), e, true);
        }
    }

    /**
     * Builds the OpenAI-compatible request JSON for Groq.
     */
    private String buildRequestBody(String systemPrompt, String userPrompt) {
        return """
                {
                  "model": %s,
                  "messages": [
                    {"role": "system", "content": %s},
                    {"role": "user", "content": %s}
                  ],
                  "response_format": {"type": "json_object"},
                  "temperature": 0.3,
                  "max_tokens": 8192
                }
                """.formatted(
                JsonUtil.toJson(model),
                JsonUtil.toJson(systemPrompt),
                JsonUtil.toJson(userPrompt)
        );
    }

    /**
     * Parses the OpenAI-compatible response format.
     */
    private String handleResponse(HttpResponse<String> response) throws LlmException {
        int status = response.statusCode();
        String body = response.body();

        if (status == 429) {
            LOG.warn("Groq rate limit hit (429)");
            throw new LlmException("Rate limit exceeded", true, 429);
        }

        if (status >= 500) {
            LOG.warn("Groq server error ({}): {}", status, truncate(body, 200));
            throw new LlmException("Server error: " + status, true, status);
        }

        if (status != 200) {
            LOG.error("Groq error ({}): {}", status, truncate(body, 500));
            throw new LlmException("API error: " + status + " — " + truncate(body, 200), false, status);
        }

        try {
            JsonNode root = JsonUtil.mapper().readTree(body);
            JsonNode choices = root.get("choices");

            if (choices == null || choices.isEmpty()) {
                throw new LlmException("No choices in Groq response", false, 200);
            }

            String text = choices.get(0).get("message").get("content").asText();
            LOG.debug("Groq response received — {} chars", text.length());
            return text;

        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Failed to parse Groq response: " + e.getMessage(), e, false);
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
