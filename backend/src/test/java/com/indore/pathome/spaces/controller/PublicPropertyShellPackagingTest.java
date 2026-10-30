package com.indore.pathome.spaces.controller;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicPropertyShellPackagingTest {
    @Test
    void packagedShellIsByteIdenticalToTheCurrentViteBuild() throws Exception {
        Path frontendBuild = Path.of("../frontend-web/dist/index.html");
        Path packagedShell = Path.of("target/classes/static/property-shell/index.html");

        assertTrue(Files.isRegularFile(frontendBuild), "Run the frontend build first");
        assertTrue(Files.isRegularFile(packagedShell), "Maven must package the Vite shell");
        assertEquals(Files.readString(frontendBuild), Files.readString(packagedShell));
        String shell = Files.readString(packagedShell);
        assertTrue(shell.contains("data-pathome-shell-title"));
        assertTrue(shell.contains("id=\"root\""));
        assertTrue(shell.contains("/assets/"));
        assertTrue(!shell.contains("/src/main.tsx"));
    }
}
