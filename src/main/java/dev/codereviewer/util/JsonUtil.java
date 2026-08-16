package dev.codereviewer.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.List;

/**
 * Centralized Jackson ObjectMapper configuration and JSON utility methods.
 *
 * <p>The singleton mapper is configured for:
 * <ul>
 *   <li>Snake-case property naming (matches most API conventions)</li>
 *   <li>Ignoring unknown properties (defensive against API changes)</li>
 *   <li>JSR-310 date/time support</li>
 *   <li>Non-null inclusion (smaller payloads)</li>
 * </ul>
 */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = createMapper();

    private JsonUtil() {
        // utility class
    }

    private static ObjectMapper createMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    /**
     * Returns the shared, pre-configured ObjectMapper instance.
     */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * Serializes an object to a JSON string.
     */
    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize to JSON", e);
        }
    }

    /**
     * Serializes an object to a pretty-printed JSON string.
     */
    public static String toPrettyJson(Object obj) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize to JSON", e);
        }
    }

    /**
     * Deserializes a JSON string to the specified type.
     */
    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to deserialize JSON to " + type.getSimpleName(), e);
        }
    }

    /**
     * Deserializes a JSON string using a TypeReference (for generics like List&lt;T&gt;).
     */
    public static <T> T fromJson(String json, TypeReference<T> typeRef) {
        try {
            return MAPPER.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to deserialize JSON", e);
        }
    }

    /**
     * Deserializes a JSON array string to a List of the specified type.
     */
    public static <T> List<T> fromJsonList(String json, Class<T> elementType) {
        try {
            return MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, elementType));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to deserialize JSON array", e);
        }
    }
}
