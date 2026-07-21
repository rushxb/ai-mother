package com.rush.rushaicodemother.infrastructure.process;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectProcessTerminatorTest {

    private final ProjectProcessTerminator terminator = new ProjectProcessTerminator(Duration.ofMillis(200));

    @Test
    void shouldRejectUnboundedTerminationGracePeriod() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectProcessTerminator(Duration.ofMinutes(6)));
    }

    @Test
    void shouldMatchAllowedNodeToolOnlyWhenCommandLineContainsProjectPath() {
        Path project = Path.of("D:/workspace/generated/vue_project_1").toAbsolutePath().normalize();
        ProcessHandle.Info matching = processInfo(
                "C:/Program Files/nodejs/node.exe",
                new String[]{project.toString(), "node_modules/.bin/vite"},
                "\"C:/Program Files/nodejs/node.exe\" \"" + project + "\" node_modules/.bin/vite"
        );
        ProcessHandle.Info otherProject = processInfo(
                "C:/Program Files/nodejs/node.exe",
                new String[]{"D:/workspace/generated/vue_project_2"},
                "node.exe D:/workspace/generated/vue_project_2"
        );
        ProcessHandle.Info prefixCollision = processInfo(
                "C:/Program Files/nodejs/node.exe",
                new String[]{project + "0"},
                "node.exe " + project + "0"
        );
        ProcessHandle.Info unrelatedExecutable = processInfo(
                "C:/Program Files/Java/bin/java.exe",
                new String[]{project.toString()},
                "java.exe " + project
        );
        ProcessHandle.Info deceptiveJavaArguments = processInfo(
                "C:/Program Files/Java/bin/java.exe",
                new String[]{"pnpm.cmd", project.toString()},
                "java.exe pnpm.cmd " + project
        );

        assertTrue(terminator.isProjectProcess(matching, project));
        assertFalse(terminator.isProjectProcess(otherProject, project));
        assertFalse(terminator.isProjectProcess(prefixCollision, project));
        assertFalse(terminator.isProjectProcess(unrelatedExecutable, project));
        assertFalse(terminator.isProjectProcess(deceptiveJavaArguments, project));
    }

    @Test
    void shouldRecognizePnpmWrappedByCommandShellWithoutAllowingArbitraryShells() {
        Path project = Path.of("D:/workspace/generated/vue_project_3").toAbsolutePath().normalize();
        ProcessHandle.Info pnpmWrapper = processInfo(
                "C:/Windows/System32/cmd.exe",
                new String[0],
                "cmd.exe /c pnpm.cmd install \"" + project + "\""
        );
        ProcessHandle.Info arbitraryShell = processInfo(
                "C:/Windows/System32/cmd.exe",
                new String[]{"/c", "echo", project.toString()},
                "cmd.exe /c echo \"" + project + "\""
        );

        assertTrue(terminator.isProjectProcess(pnpmWrapper, project));
        assertFalse(terminator.isProjectProcess(arbitraryShell, project));
    }

    @Test
    void shouldTerminateParentAndDescendantProcesses() throws Exception {
        Process parent = startJavaProcess(ProcessTreeParent.class.getName());
        BufferedReader output = parent.inputReader();
        CompletableFuture<String> childPidLine = CompletableFuture.supplyAsync(() -> {
            try {
                return output.readLine();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
        long childPid = Long.parseLong(childPidLine.get(5, TimeUnit.SECONDS));
        ProcessHandle child = ProcessHandle.of(childPid).orElseThrow();
        assertTrue(parent.isAlive());
        assertTrue(child.isAlive());

        assertTrue(terminator.terminate(parent));

        assertFalse(parent.isAlive());
        awaitProcessExit(child, Duration.ofSeconds(2));
        assertFalse(child.isAlive());
    }

    private ProcessHandle.Info processInfo(String command, String[] arguments, String commandLine) {
        ProcessHandle.Info info = mock(ProcessHandle.Info.class);
        when(info.command()).thenReturn(Optional.of(command));
        when(info.arguments()).thenReturn(Optional.of(arguments));
        when(info.commandLine()).thenReturn(Optional.of(commandLine));
        return info;
    }

    private Process startJavaProcess(String mainClass) throws Exception {
        Path javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java"
        );
        return new ProcessBuilder(
                javaExecutable.toString(),
                "-Xms8m",
                "-Xmx32m",
                "-XX:MaxMetaspaceSize=64m",
                "-XX:+UseSerialGC",
                "-XX:ActiveProcessorCount=1",
                "-cp",
                System.getProperty("java.class.path"),
                mainClass
        ).start();
    }

    private void awaitProcessExit(ProcessHandle process, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (process.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    public static final class ProcessTreeParent {

        private ProcessTreeParent() {
        }

        public static void main(String[] args) throws Exception {
            Path javaExecutable = Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    System.getProperty("os.name", "").toLowerCase().contains("windows")
                            ? "java.exe"
                            : "java"
            );
            Process child = new ProcessBuilder(
                    javaExecutable.toString(),
                    "-Xms8m",
                    "-Xmx32m",
                    "-XX:MaxMetaspaceSize=64m",
                    "-XX:+UseSerialGC",
                    "-XX:ActiveProcessorCount=1",
                    "-cp",
                    System.getProperty("java.class.path"),
                    ProcessTreeChild.class.getName()
            ).start();
            System.out.println(child.pid());
            System.out.flush();
            Thread.sleep(Duration.ofMinutes(1));
        }
    }

    public static final class ProcessTreeChild {

        private ProcessTreeChild() {
        }

        public static void main(String[] args) throws Exception {
            Thread.sleep(Duration.ofMinutes(1));
        }
    }
}
