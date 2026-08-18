package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.infrastructure.process.NodeToolchain;
import com.rush.rushaicodemother.security.workspace.GeneratedNodeWorkspaceValidator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("external")
class VitePreviewExternalTest {

    private static final Pattern WS_TOKEN_PATTERN = Pattern.compile(
            "const wsToken = \\\"([^\\\"]+)\\\";"
    );

    @Test
    void controlledLauncherMustServeScopedAssetsAndHmrWebSocket() throws Exception {
        Path projectDirectory = Path.of("rush-ai-code-mother-frontend")
                .toAbsolutePath()
                .normalize();
        assertTrue(Files.isRegularFile(projectDirectory.resolve("node_modules/vite/bin/vite.js")));
        int port = availablePort();
        NodeToolchain nodeToolchain = mock(NodeToolchain.class);
        when(nodeToolchain.nodeExecutable()).thenReturn("node");
        GeneratedNodeWorkspaceValidator workspaceValidator = mock(GeneratedNodeWorkspaceValidator.class);
        when(workspaceValidator.validate(projectDirectory)).thenReturn(
                new GeneratedNodeWorkspaceValidator.Validation(true, projectDirectory, null)
        );
        ViteLauncherResolver resolver = new ViteLauncherResolver(
                nodeToolchain,
                new DevServerPreviewPathFactory("/api"),
                workspaceValidator
        );
        List<String> command = resolver.resolve(projectDirectory, port, 21L);
        Process process = new ProcessBuilder(command)
                .directory(projectDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        StringBuilder processOutput = new StringBuilder();
        Thread outputReader = Thread.ofVirtual().start(() -> captureOutput(process, processOutput));
        WebSocket webSocket = null;
        try {
            String base = "/api/app/dev-server/proxy/21/";
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            String html = awaitBody(
                    client,
                    URI.create("http://127.0.0.1:" + port + base),
                    process,
                    processOutput
            );
            assertTrue(html.contains("src=\"" + base + "@vite/client\""));
            assertTrue(html.contains("src=\"" + base + "src/main.ts\""));

            String viteClient = awaitBody(
                    client,
                    URI.create("http://127.0.0.1:" + port + base + "@vite/client"),
                    process,
                    processOutput
            );
            assertTrue(viteClient.contains("const base = \"" + base + "\""));
            Matcher tokenMatcher = WS_TOKEN_PATTERN.matcher(viteClient);
            assertTrue(tokenMatcher.find());

            CompletableFuture<String> firstMessage = new CompletableFuture<>();
            webSocket = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .subprotocols("vite-hmr")
                    .buildAsync(
                            URI.create("ws://127.0.0.1:" + port + base
                                    + "?token=" + tokenMatcher.group(1)),
                            new FirstTextMessageListener(firstMessage)
                    )
                    .get(10, TimeUnit.SECONDS);

            assertEquals("vite-hmr", webSocket.getSubprotocol());
            assertTrue(firstMessage.get(10, TimeUnit.SECONDS).contains("\"type\":\"connected\""));
        } finally {
            if (webSocket != null) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done")
                        .orTimeout(2, TimeUnit.SECONDS)
                        .exceptionally(ignored -> null)
                        .join();
            }
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            outputReader.join(Duration.ofSeconds(2));
        }
    }

    private String awaitBody(HttpClient client,
                             URI uri,
                             Process process,
                             StringBuilder processOutput) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        Throwable latestFailure = null;
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException(
                        "Vite Preview exited with code " + process.exitValue()
                                + ": " + snapshot(processOutput)
                );
            }
            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build(),
                        HttpResponse.BodyHandlers.ofString()
                );
                if (response.statusCode() == 200) {
                    return response.body();
                }
                latestFailure = new IllegalStateException("unexpected status " + response.statusCode());
            } catch (Exception requestFailure) {
                latestFailure = requestFailure;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException(
                "Vite Preview did not become ready: " + snapshot(processOutput),
                latestFailure
        );
    }

    private void captureOutput(Process process, StringBuilder output) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (output) {
                    if (output.length() < 32_768) {
                        output.append(line).append(System.lineSeparator());
                    }
                }
            }
        } catch (Exception ignored) {
            // Process shutdown closes the stream; captured output remains available for diagnostics.
        }
    }

    private String snapshot(StringBuilder output) {
        synchronized (output) {
            return output.toString();
        }
    }

    private int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class FirstTextMessageListener implements WebSocket.Listener {

        private final CompletableFuture<String> firstMessage;
        private final StringBuilder buffer = new StringBuilder();

        private FirstTextMessageListener(CompletableFuture<String> firstMessage) {
            this.firstMessage = firstMessage;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last && !firstMessage.isDone()) {
                firstMessage.complete(buffer.toString());
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            firstMessage.completeExceptionally(error);
        }
    }
}
