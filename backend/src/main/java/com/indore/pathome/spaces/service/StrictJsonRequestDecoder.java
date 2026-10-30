package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Rejects unknown fields on the new property contracts instead of silently ignoring injections. */
@Service
public class StrictJsonRequestDecoder {
    private final ObjectMapper mapper;

    public StrictJsonRequestDecoder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public <T> T decode(JsonNode node, Class<T> type) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("A typed request object is required.");
        }
        try {
            return mapper.readerFor(type)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(node.traverse(mapper));
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException(
                    "The request body contains an unsupported or invalid field.");
        }
    }

    public <T> List<T> decodeList(JsonNode node, Class<T> type) {
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException("A typed request list is required.");
        }
        List<T> values = new ArrayList<>();
        node.forEach(element -> values.add(decode(element, type)));
        return List.copyOf(values);
    }
}

