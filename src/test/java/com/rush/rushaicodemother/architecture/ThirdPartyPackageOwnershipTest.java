package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ThirdPartyPackageOwnershipTest {

    private static final Pattern PACKAGE_DECLARATION =
            Pattern.compile("(?m)^\\s*package\\s+([^;]+);");
    private static final List<String> FORBIDDEN_PACKAGE_PREFIXES = List.of(
            "dev.langchain4j"
    );

    @Test
    void productionSourcesMustNotShadowThirdPartyPackages() throws IOException {
        Path sourceRoot = Path.of(System.getProperty("projectBaseDir", "."), "src", "main", "java");
        List<String> violations = new ArrayList<>();

        try (Stream<Path> sourceFiles = Files.walk(sourceRoot)) {
            for (Path sourceFile : sourceFiles
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                String packageName = packageName(sourceFile);
                if (FORBIDDEN_PACKAGE_PREFIXES.stream()
                        .anyMatch(prefix -> packageName.equals(prefix) || packageName.startsWith(prefix + "."))) {
                    violations.add(sourceRoot.relativize(sourceFile) + " declares package " + packageName);
                }
            }
        }

        violations.sort(Comparator.naturalOrder());
        assertTrue(violations.isEmpty(), () ->
                "Third-party packages are owned by their dependencies. Add adapters under "
                        + "com.rush.rushaicodemother instead:\n - " + String.join("\n - ", violations));
    }

    private String packageName(Path sourceFile) throws IOException {
        Matcher matcher = PACKAGE_DECLARATION.matcher(Files.readString(sourceFile));
        return matcher.find() ? matcher.group(1).trim() : "";
    }
}
