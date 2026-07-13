package com.rush.rushaicodemother.infrastructure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards Java source files against encoding corruption and lost comment text. */
class SourceEncodingComplianceTest {

    private static final byte[] UTF_8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final List<Path> SOURCE_ROOTS = List.of(
            Path.of("src", "main", "java"),
            Path.of("src", "test", "java")
    );

    @Test
    void javaSourcesMustBeCleanUtf8WithoutCorruptedComments() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path sourceRoot : SOURCE_ROOTS) {
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            try (var paths = Files.walk(sourceRoot)) {
                paths.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"))
                        .forEach(path -> inspect(path, violations));
            }
        }
        assertTrue(violations.isEmpty(), () -> String.join(System.lineSeparator(), violations));
    }

    private void inspect(Path path, List<String> violations) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException exception) {
            violations.add(path + ": cannot read source: " + exception.getMessage());
            return;
        }
        if (startsWithUtf8Bom(bytes)) {
            violations.add(path + ": UTF-8 BOM is not allowed");
        }
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            violations.add(path + ": invalid UTF-8: " + exception.getMessage());
            return;
        }
        inspectCharacters(path, content, violations);
        inspectComments(path, content, violations);
    }

    private void inspectCharacters(Path path, String content, List<String> violations) {
        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            if (current == '\uFFFD') {
                violations.add(path + ": contains Unicode replacement character");
                return;
            }
            if (current >= '\uE000' && current <= '\uF8FF') {
                violations.add(path + ": contains a private-use character");
                return;
            }
        }
    }

    private void inspectComments(Path path, String content, List<String> violations) {
        String[] lines = content.split("\\R", -1);
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            String trimmed = lines[lineIndex].stripLeading();
            boolean commentLine = trimmed.startsWith("//")
                    || trimmed.startsWith("/*")
                    || trimmed.startsWith("*");
            if (commentLine && trimmed.contains("??")) {
                violations.add(path + ":" + (lineIndex + 1) + ": suspicious question marks in comment");
            }
        }
    }

    private boolean startsWithUtf8Bom(byte[] bytes) {
        return bytes.length >= UTF_8_BOM.length
                && bytes[0] == UTF_8_BOM[0]
                && bytes[1] == UTF_8_BOM[1]
                && bytes[2] == UTF_8_BOM[2];
    }
}