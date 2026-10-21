package com.indore.pathome.spaces.support;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.MethodDescriptor;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.MethodOrdererContext;

/**
 * Test-only C26 diagnostic ordering. With no {@code pathome.c26.order} property this is a no-op;
 * with a comma-separated method list it orders exactly those methods for the bounded experiment.
 */
public final class C26DiagnosticOrderer implements MethodOrderer {
    static final String ORDER_PROPERTY = "pathome.c26.order";

    @Override
    public void orderMethods(MethodOrdererContext context) {
        String requested = System.getProperty(ORDER_PROPERTY);
        if (requested == null || requested.isBlank()) return;
        List<String> names = Arrays.stream(requested.split(",")).map(String::trim).toList();
        context.getMethodDescriptors().sort((left, right) ->
                Integer.compare(indexOf(names, left), indexOf(names, right)));
    }

    private static int indexOf(List<String> names, MethodDescriptor descriptor) {
        int index = names.indexOf(descriptor.getMethod().getName());
        return index < 0 ? Integer.MAX_VALUE : index;
    }
}
