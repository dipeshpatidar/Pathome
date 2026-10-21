package com.indore.pathome.spaces.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Test-only proof of actual execution order. It writes nothing unless the probe file property is set.
 */
public final class C26ExecutionProbe implements BeforeTestExecutionCallback, AfterTestExecutionCallback {
    static final String FILE_PROPERTY = "pathome.c26.probe";

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        append("START", context);
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        append("END", context);
    }

    private static void append(String phase, ExtensionContext context) {
        String file = System.getProperty(FILE_PROPERTY);
        if (file == null || file.isBlank()) return;
        String line = phase + " " + System.currentTimeMillis() + " "
                + context.getRequiredTestMethod().getName() + System.lineSeparator();
        try {
            Files.writeString(Path.of(file), line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            throw new IllegalStateException("C26 execution probe could not append", failure);
        }
    }
}
