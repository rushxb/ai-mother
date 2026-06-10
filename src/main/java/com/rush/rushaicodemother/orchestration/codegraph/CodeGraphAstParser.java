package com.rush.rushaicodemother.orchestration.codegraph;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CodeGraphAstParser {

    private static final Pattern JS_IMPORT_FROM = Pattern.compile("(?m)\\bimport\\s+(?:[^;]*?\\s+from\\s+)?['\"]([^'\"]+)['\"]");
    private static final Pattern JS_EXPORT_FROM = Pattern.compile("(?m)\\bexport\\s+[^;]*?\\s+from\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern JS_SYMBOL = Pattern.compile(
            "\\b(?:export\\s+default\\s+)?(?:export\\s+)?(?:async\\s+)?(?:function|class|interface|type|enum|const|let|var)\\s+([A-Za-z_$][\\w$-]*)"
    );
    private static final Pattern VUE_NAME = Pattern.compile("\\bname\\s*:\\s*['\"]([A-Za-z_$][\\w$-]*)['\"]");
    private static final Pattern GO_IMPORT_BLOCK = Pattern.compile("(?s)import\\s*\\((.*?)\\)");
    private static final Pattern GO_IMPORT_SINGLE = Pattern.compile("(?m)^\\s*import\\s+\"([^\"]+)\"");
    private static final Pattern GO_IMPORT_LINE = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern GO_FUNC = Pattern.compile("\\bfunc\\s+(?:\\([^)]*\\)\\s*)?([A-Za-z_][\\w]*)\\s*\\(");
    private static final Pattern GO_TYPE = Pattern.compile("\\btype\\s+([A-Za-z_][\\w]*)\\s+(struct|interface|func)?");
    private static final Pattern SQL_TABLE = Pattern.compile("(?i)\\bcreate\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([A-Za-z_][\\w]*)");
    private static final Pattern SQL_COLUMN = Pattern.compile("(?m)^\\s*([A-Za-z_][\\w]*)\\s+(?:text|integer|real|blob|boolean|varchar|datetime|timestamp|date)\\b", Pattern.CASE_INSENSITIVE);

    private final StructuredSyntaxValidationService syntaxValidationService;

    public CodeGraphAstParser(StructuredSyntaxValidationService syntaxValidationService) {
        this.syntaxValidationService = syntaxValidationService;
    }

    public CodeGraphFileNode parse(Path rootDir, String relativePath, String content) {
        String normalizedPath = normalizePath(relativePath);
        String extension = normalizeExtension(FileUtil.extName(normalizedPath));
        List<CodeGraphImport> imports = new ArrayList<>();
        List<CodeGraphSymbol> symbols = new ArrayList<>();
        if (Set.of("vue", "js", "ts", "jsx", "tsx", "mjs").contains(extension)) {
            parseJavaScriptLike(rootDir, normalizedPath, content, imports, symbols);
        } else if ("go".equals(extension)) {
            parseGo(rootDir, normalizedPath, content, imports, symbols);
        } else if ("sql".equals(extension)) {
            parseSql(normalizedPath, content, symbols);
        }
        StructuredSyntaxValidationService.ValidationResult validation = syntaxValidationService.validate(normalizedPath, content);
        return new CodeGraphFileNode(normalizedPath, extension, imports, symbols, validation.errors());
    }

    private void parseJavaScriptLike(Path rootDir,
                                     String relativePath,
                                     String content,
                                     List<CodeGraphImport> imports,
                                     List<CodeGraphSymbol> symbols) {
        String scriptContent = relativePath.endsWith(".vue") ? extractScriptContent(content) : StrUtil.blankToDefault(content, "");
        addJsImports(rootDir, relativePath, scriptContent, imports);
        addJsImports(rootDir, relativePath, StrUtil.blankToDefault(content, ""), imports);
        LinkedHashSet<String> symbolNames = new LinkedHashSet<>();
        symbolNames.add(FileUtil.mainName(relativePath.substring(relativePath.lastIndexOf('/') + 1)));
        addMatches(scriptContent, JS_SYMBOL, symbolNames);
        addMatches(scriptContent, VUE_NAME, symbolNames);
        for (String symbolName : symbolNames) {
            if (StrUtil.isNotBlank(symbolName) && !"index".equalsIgnoreCase(symbolName)) {
                symbols.add(new CodeGraphSymbol(symbolName, inferJsSymbolKind(scriptContent, symbolName), relativePath));
            }
        }
    }

    private void addJsImports(Path rootDir, String relativePath, String content, List<CodeGraphImport> imports) {
        var importMatcher = JS_IMPORT_FROM.matcher(StrUtil.blankToDefault(content, ""));
        while (importMatcher.find()) {
            addImport(rootDir, relativePath, importMatcher.group(1), "js_import", imports);
        }
        var exportMatcher = JS_EXPORT_FROM.matcher(StrUtil.blankToDefault(content, ""));
        while (exportMatcher.find()) {
            addImport(rootDir, relativePath, exportMatcher.group(1), "js_export", imports);
        }
    }

    private void parseGo(Path rootDir,
                         String relativePath,
                         String content,
                         List<CodeGraphImport> imports,
                         List<CodeGraphSymbol> symbols) {
        var blockMatcher = GO_IMPORT_BLOCK.matcher(StrUtil.blankToDefault(content, ""));
        while (blockMatcher.find()) {
            var lineMatcher = GO_IMPORT_LINE.matcher(blockMatcher.group(1));
            while (lineMatcher.find()) {
                addImport(rootDir, relativePath, lineMatcher.group(1), "go_import", imports);
            }
        }
        var singleMatcher = GO_IMPORT_SINGLE.matcher(StrUtil.blankToDefault(content, ""));
        while (singleMatcher.find()) {
            addImport(rootDir, relativePath, singleMatcher.group(1), "go_import", imports);
        }
        addSymbolMatches(relativePath, content, GO_FUNC, "go_function", symbols);
        addSymbolMatches(relativePath, content, GO_TYPE, "go_type", symbols);
    }

    private void parseSql(String relativePath, String content, List<CodeGraphSymbol> symbols) {
        addSymbolMatches(relativePath, content, SQL_TABLE, "sql_table", symbols);
        addSymbolMatches(relativePath, content, SQL_COLUMN, "sql_column", symbols);
    }

    private void addImport(Path rootDir,
                           String sourceFile,
                           String importedPath,
                           String kind,
                           List<CodeGraphImport> imports) {
        String normalizedImport = StrUtil.blankToDefault(importedPath, "").trim();
        if (normalizedImport.isBlank()) {
            return;
        }
        imports.add(new CodeGraphImport(sourceFile, normalizedImport, resolveImport(rootDir, sourceFile, normalizedImport), kind));
    }

    private String resolveImport(Path rootDir, String sourceFile, String importedPath) {
        if (rootDir == null || StrUtil.isBlank(sourceFile) || StrUtil.isBlank(importedPath)) {
            return "";
        }
        String normalizedImport = importedPath.replace('\\', '/');
        if (normalizedImport.startsWith(".") || normalizedImport.startsWith("@/")) {
            Path base = normalizedImport.startsWith("@/")
                    ? rootDir.resolve("src").resolve(normalizedImport.substring(2))
                    : rootDir.resolve(sourceFile).getParent().resolve(normalizedImport);
            String resolved = resolveExistingRelative(rootDir, base.normalize());
            return StrUtil.blankToDefault(resolved, "");
        }
        String goResolved = resolveGoInternalImport(rootDir, normalizedImport);
        return StrUtil.blankToDefault(goResolved, "");
    }

    private String resolveExistingRelative(Path rootDir, Path base) {
        List<Path> candidates = List.of(
                base,
                Path.of(base.toString() + ".ts"),
                Path.of(base.toString() + ".js"),
                Path.of(base.toString() + ".vue"),
                Path.of(base.toString() + ".tsx"),
                Path.of(base.toString() + ".jsx"),
                base.resolve("index.ts"),
                base.resolve("index.js"),
                base.resolve("index.vue")
        );
        for (Path candidate : candidates) {
            if (java.nio.file.Files.isRegularFile(candidate)) {
                return normalizePath(rootDir.relativize(candidate).toString());
            }
        }
        return "";
    }

    private String resolveGoInternalImport(Path rootDir, String importedPath) {
        int internalIndex = importedPath.indexOf("/internal/");
        if (internalIndex < 0) {
            return "";
        }
        String internalPath = importedPath.substring(internalIndex + 1);
        Path directory = rootDir.resolve(internalPath);
        if (!java.nio.file.Files.isDirectory(directory)) {
            return "";
        }
        try (var stream = java.nio.file.Files.list(directory)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".go"))
                    .findFirst()
                    .map(path -> normalizePath(rootDir.relativize(path).toString()))
                    .orElse("");
        } catch (Exception ignored) {
            return "";
        }
    }

    private void addSymbolMatches(String relativePath,
                                  String content,
                                  Pattern pattern,
                                  String kind,
                                  List<CodeGraphSymbol> symbols) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        addMatches(content, pattern, names);
        for (String name : names) {
            symbols.add(new CodeGraphSymbol(name, kind, relativePath));
        }
    }

    private void addMatches(String content, Pattern pattern, LinkedHashSet<String> values) {
        var matcher = pattern.matcher(StrUtil.blankToDefault(content, ""));
        while (matcher.find()) {
            String value = matcher.group(1);
            if (StrUtil.isNotBlank(value)) {
                values.add(value.trim());
            }
        }
    }

    private String inferJsSymbolKind(String content, String symbolName) {
        String normalized = StrUtil.blankToDefault(content, "");
        if (normalized.contains("function " + symbolName)) {
            return "function";
        }
        if (normalized.contains("class " + symbolName)) {
            return "class";
        }
        if (normalized.contains("interface " + symbolName)) {
            return "interface";
        }
        return "component_or_export";
    }

    private String extractScriptContent(String content) {
        StringBuilder builder = new StringBuilder();
        var matcher = Pattern.compile("(?is)<script\\b[^>]*>(.*?)</script>").matcher(StrUtil.blankToDefault(content, ""));
        while (matcher.find()) {
            builder.append(matcher.group(1)).append('\n');
        }
        return builder.toString();
    }

    private String normalizePath(String path) {
        return StrUtil.blankToDefault(path, "").replace('\\', '/');
    }

    private String normalizeExtension(String extension) {
        return StrUtil.blankToDefault(extension, "").toLowerCase(Locale.ROOT);
    }
}
