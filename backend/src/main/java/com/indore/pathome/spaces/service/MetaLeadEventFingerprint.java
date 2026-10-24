package com.indore.pathome.spaces.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Stable identity for one supported leadgen event. The signed delivery envelope and its batching are
 * deliberately excluded; only provider/Page/form/leadgen identity and the event value participate.
 */
public final class MetaLeadEventFingerprint {
    public static final String VERSION = "EVENT_CANONICAL_V2";

    private MetaLeadEventFingerprint() {}

    public static String fingerprint(ObjectMapper mapper, String pageId, String formId, String leadgenId,
                                     JsonNode value) {
        try {
            ObjectNode identity = mapper.createObjectNode();
            identity.put("provider", "META_LEADGEN");
            identity.put("pageId", pageId);
            identity.put("formId", formId);
            identity.put("leadgenId", leadgenId);
            identity.set("value", canonicalize(value, mapper));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsBytes(identity)));
        } catch (Exception exception) {
            throw new IllegalStateException("Meta event could not be fingerprinted");
        }
    }

    private static JsonNode canonicalize(JsonNode node, ObjectMapper mapper) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node == null ? mapper.nullNode() : node.deepCopy();
        }
        if (node.isArray()) {
            List<String> encoded = new ArrayList<>();
            for (JsonNode item : node) {
                encoded.add(canonicalize(item, mapper).toString());
            }
            encoded.sort(String::compareTo);
            ArrayNode array = mapper.createArrayNode();
            for (String item : encoded) {
                try {
                    array.add(mapper.readTree(item));
                } catch (Exception exception) {
                    throw new IllegalStateException("Canonical Meta event is invalid");
                }
            }
            return array;
        }
        ObjectNode object = mapper.createObjectNode();
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        names.sort(String::compareTo);
        for (String name : names) {
            object.set(name, canonicalize(node.get(name), mapper));
        }
        return object;
    }
}
