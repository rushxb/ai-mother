package com.rush.rushaicodemother.architecture;

import com.rush.rushaicodemother.RushAiCodeMotherApplication;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Loads compiled production classes that still have a corresponding source file.
 *
 * <p>Maven incremental builds can retain class files for deleted sources when {@code clean} is
 * intentionally not used. Architecture tests must therefore use the source tree as the ownership
 * authority instead of treating every class under {@code target/classes} as current production
 * code.</p>
 */
final class ProductionClassScanner {

    private static final Path MAIN_SOURCE_ROOT = Path.of("src", "main", "java")
            .toAbsolutePath()
            .normalize();

    private ProductionClassScanner() {
    }

    static List<Class<?>> load(String basePackage)
            throws IOException, URISyntaxException, ClassNotFoundException {
        Path classesRoot = Path.of(RushAiCodeMotherApplication.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        Path packageRoot = classesRoot.resolve(basePackage.replace('.', '/'));
        if (!Files.isDirectory(packageRoot)) {
            throw new IllegalStateException("Production classes directory not found: " + packageRoot);
        }

        List<Class<?>> classes = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(packageRoot)) {
            List<String> classNames = paths
                    .filter(Files::isRegularFile)
                    .map(classesRoot::relativize)
                    .map(Path::toString)
                    .filter(path -> path.endsWith(".class"))
                    .filter(path -> !path.endsWith("module-info.class"))
                    .map(ProductionClassScanner::toClassName)
                    .filter(ProductionClassScanner::hasProductionSourceFile)
                    .sorted()
                    .toList();
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            for (String className : classNames) {
                classes.add(Class.forName(className, false, classLoader));
            }
        }
        return classes;
    }

    private static String toClassName(String relativeClassFile) {
        return relativeClassFile
                .substring(0, relativeClassFile.length() - ".class".length())
                .replace('\\', '.')
                .replace('/', '.');
    }

    private static boolean hasProductionSourceFile(String className) {
        String topLevelClassName = className.contains("$")
                ? className.substring(0, className.indexOf('$'))
                : className;
        Path sourceFile = MAIN_SOURCE_ROOT.resolve(topLevelClassName.replace('.', '/') + ".java");
        return Files.isRegularFile(sourceFile);
    }
}
