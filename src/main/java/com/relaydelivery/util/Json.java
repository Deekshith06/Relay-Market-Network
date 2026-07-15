package com.relaydelivery.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;

public final class Json {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    // Reject extra monetary or stock fields instead of silently trusting client-shaped payloads.
    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private Json() {}

    public static <T> T read(InputStream stream, Class<T> type) throws IOException {
        byte[] body = stream.readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) throw new ApiException(413, "Request body is too large");
        try {
            return MAPPER.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("Malformed JSON request");
        }
    }

    public static byte[] bytes(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize response", e);
        }
    }

    public static String stringify(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Could not serialize value", e); }
    }

    public static <T> T parse(String value, Class<T> type) {
        try { return MAPPER.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Stored JSON is invalid", e); }
    }
}
