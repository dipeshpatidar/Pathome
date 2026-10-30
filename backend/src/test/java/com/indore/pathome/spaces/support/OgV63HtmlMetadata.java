package com.indore.pathome.spaces.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.util.HtmlUtils;

/** Strict parser for the approved public metadata tags; it does not execute the returned HTML. */
final class OgV63HtmlMetadata {
    private static final Pattern META = Pattern.compile("<meta\\b[^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern TITLE = Pattern.compile(
            "<title(?:\\s[^>]*)?>(.*?)</title>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CANONICAL = Pattern.compile(
            "<link\\s+rel=\"canonical\"\\s+href=\"([^\"]*)\"\\s+"
                    + "data-pathome-public-metadata=\"true\"\\s*>",
            Pattern.CASE_INSENSITIVE);

    final Map<String, List<String>> named = new LinkedHashMap<>();
    final Map<String, List<String>> properties = new LinkedHashMap<>();
    final List<String> titles = new ArrayList<>();
    final List<String> canonicals = new ArrayList<>();

    private OgV63HtmlMetadata(String html) {
        Matcher meta = META.matcher(html);
        while (meta.find()) {
            Map<String, String> attributes = attributes(meta.group());
            String name = attributes.get("name");
            String property = attributes.get("property");
            String content = decode(attributes.get("content"));
            if (name != null && content != null) {
                named.computeIfAbsent(name, ignored -> new ArrayList<>()).add(content);
            }
            if (property != null && content != null) {
                properties.computeIfAbsent(property, ignored -> new ArrayList<>()).add(content);
            }
        }
        Matcher title = TITLE.matcher(html);
        while (title.find()) titles.add(decode(title.group(1)).strip());
        Matcher canonical = CANONICAL.matcher(html);
        while (canonical.find()) canonicals.add(decode(canonical.group(1)));
    }

    static OgV63HtmlMetadata parse(String html) {
        return new OgV63HtmlMetadata(html);
    }

    String singleProperty(String name) {
        return single(properties, "property " + name);
    }

    String singleNamed(String name) {
        return single(named, "name " + name);
    }

    private static String single(Map<String, List<String>> values, String label) {
        List<String> found = values.getOrDefault(label.substring(label.indexOf(' ') + 1),
                List.of());
        OgV63Target.require(found.size() == 1, "metadata cardinality differs for " + label);
        return found.get(0);
    }

    private static Map<String, String> attributes(String tag) {
        Map<String, String> attributes = new LinkedHashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(tag);
        while (matcher.find()) {
            OgV63Target.require(attributes.putIfAbsent(
                    matcher.group(1).toLowerCase(), decode(matcher.group(2))) == null,
                    "duplicate HTML attribute");
        }
        return attributes;
    }

    private static String decode(String value) {
        return value == null ? null : HtmlUtils.htmlUnescape(value);
    }
}
