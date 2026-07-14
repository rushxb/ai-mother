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

    private static final Set<String> JAVASCRIPT_LIKE_EXTENSIONS = Set.of("vue", "js", "ts", "jsx", "tsx", "mjs");
    private static final List<String> JAVASCRIPT_FILE_SUFFIXES = List.of(".ts", ".js", ".vue", ".tsx", ".jsx", ".mjs");
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

    public CodeGraphFileNode parse(String relativePath, String content, Set<String> knownFiles) {
        String normalizedPath = normalizePath(relativePath);
        String extension = normalizeExtension(FileUtil.extName(normalizedPath));
        Set<String> normalizedKnownFiles = normalizeKnownFiles(knownFiles);
        List<CodeGraphImport> imports = new ArrayList<>();
        List<CodeGraphSymbol> symbols = new ArrayList<>();
        if (JAVASCRIPT_LIKE_EXTENSIONS.contains(extension)) {
            parseJavaScriptLike(normalizedPath, content, normalizedKnownFiles, imports, symbols);
        } else if ("go".equals(extension)) {
            parseGo(normalizedPath, content, normalizedKnownFiles, imports, symbols);
        } else if ("sql".equals(extension)) {
            parseSql(normalizedPath, content, symbols);
        }
        StructuredSyntaxValidationService.ValidationResult validation = syntaxValidationService.validate(normalizedPath, content);
        return new CodeGraphFileNode(normalizedPath, extension, imports, symbols, validation.errors());
    }

    private void parseJavaScriptLike(String relativePath,
                                     String content,
                                     Set<String> knownFiles,
                                     List<CodeGraphImport> imports,
                                     List<CodeGraphSymbol> symbols) {
        String scriptContent = relativePath.endsWith(".vue") ? extractScriptContent(content) : StrUtil.blankToDefault(content, "");
        addJsImports(relativePath, scriptContent, knownFiles, imports);
        addJsImports(relativePath, StrUtil.blankToDefault(content, ""), knownFiles, imports);
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

    private void addJsImports(String relativePath,
                              String content,
                              Set<String> knownFiles,
                              List<CodeGraphImport> imports) {
        var importMatcher = JS_IMPORT_FROM.matcher(StrUtil.blankToDefault(content, ""));
        while (importMatcher.find()) {
            addImport(relativePath, importMatcher.group(1), "js_import", knownFiles, imports);
        }
        var exportMatcher = JS_EXPORT_FROM.matcher(StrUtil.blankToDefault(content, ""));
        while (exportMatcher.find()) {
            addImport(relativePath, exportMatcher.group(1), "js_export", knownFiles, imports);
        }
    }

    private void parseGo(String relativePath,
                         String content,
                         Set<String> knownFiles,
                         List<CodeGraphImport> imports,
                         List<CodeGraphSymbol> symbols) {
        var blockMatcher = GO_IMPORT_BLOCK.matcher(StrUtil.blankToDefault(content, ""));
        while (blockMatcher.find()) {
            var lineMatcher = GO_IMPORT_LINE.matcher(blockMatcher.group(1));
            while (lineMatcher.find()) {
                addImport(relativePath, lineMatcher.group(1), "go_import", knownFiles, imports);
            }
        }
        var singleMatcher = GO_IMPORT_SINGLE.matcher(StrUtil.blankToDefault(content, ""));
        while (singleMatcher.find()) {
            addImport(relativePath, singleMatcher.group(1), "go_import", knownFiles, imports);
        }
        addSymbolMatches(relativePath, content, GO_FUNC, "go_function", symbols);
        addSymbolMatches(relativePath, content, GO_TYPE, "go_type", symbols);
    }

    private void parseSql(String relativePath, String content, List<CodeGraphSymbol> symbols) {
        addSymbolMatches(relativePath, content, SQL_TABLE, "sql_table", symbols);
        addSymbolMatches(relativePath, content, SQL_COLUMN, "sql_column", symbols);
    }

    private void addImport(String sourceFile,
                           String importedPath,
                           String kind,
                           Set<String> knownFiles,
                           List<CodeGraphImport> imports) {
        String normalizedImport = StrUtil.blankToDefault(importedPath, "").trim();
        if (normalizedImport.isBlank()) {
            return;
        }
        imports.add(new CodeGraphImport(
                sourceFile,
                normalizedImport,
                resolveImport(sourceFile, normalizedImport, knownFiles),
                kind
        ));
    }

    private String resolveImport(String sourceFile, String importedPath, Set<String> knownFiles) {
        if (StrUtil.isBlank(sourceFile) || StrUtil.isBlank(importedPath) || knownFiles.isEmpty()) {
            return "";
        }
        String normalizedImport = importedPath.replace('\\', '/');
        if (normalizedImport.startsWith(".") || normalizedImport.startsWith("@/")) {
            return resolveJavaScriptImport(sourceFile, normalizedImport, knownFiles);
        }
        return resolveGoInternalImport(normalizedImport, knownFiles);
    }

    private String resolveJavaScriptImport(String sourceFile, String importedPath, Set<String> knownFiles) {
        try {
            Path base;
            if (importedPath.startsWith("@/")) {
                base = Path.of("src").resolve(importedPath.substring(2));
            } else {
                Path sourcePath = Path.of(sourceFile);
                Path sourceDirectory = sourcePath.getParent();
                base = (sourceDirectory == null ? Path.of("") : sourceDirectory).resolve(importedPath);
            }
            String normalizedBase = normalizeRelativeCandidate(base);
            if (normalizedBase.isBlank()) {
                return "";
            }
            LinkedHashSet<String> candidates = new LinkedHashSet<>();
            candidates.add(normalizedBase);
            JAVASCRIPT_FILE_SUFFIXES.forEach(suffix -> candidates.add(normalizedBase + suffix));
            JAVASCRIPT_FILE_SUFFIXES.forEach(suffix -> candidates.add(normalizedBase + "/index" + suffix));
            for (String candidate : candidates) {
                if (knownFiles.contains(candidate)) {
                    return candidate;
                }
            }
        } catch (RuntimeException ignored) {
            // 非法导入路径仅作为未解析依赖处理，不能触发整个代码图构建失败。
        }
        return "";
    }

    private String resolveGoInternalImport(String importedPath, Set<String> knownFiles) {
        int internalIndex = importedPath.indexOf("/internal/");
        String internalPath;
        if (internalIndex >= 0) {
            internalPath = importedPath.substring(internalIndex + 1);
        } else if (importedPath.startsWith("internal/")) {
            internalPath = importedPath;
        } else {
            return "";
        }
        String directoryPrefix = normalizePath(internalPath).replaceAll("/+$", "") + "/";
        return knownFiles.stream()
                .filter(path -> path.startsWith(directoryPrefix))
                .filter(path -> path.endsWith(".go"))
                .filter(path -> path.indexOf('/', directoryPrefix.length()) < 0)
                .sorted()
                .findFirst()
                .orElse("");
    }

    private Set<String> normalizeKnownFiles(Set<String> knownFiles) {
        if (knownFiles == null || knownFiles.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String knownFile : knownFiles) {
            String normalizedPath = normalizePath(knownFile);
            if (StrUtil.isNotBlank(normalizedPath)) {
                normalized.add(normalizedPath);
            }
        }
        return Set.copyOf(normalized);
    }

    private String normalizeRelativeCandidate(Path candidate) {
        Path normalized = candidate.normalize();
        String normalizedPath = normalizePath(normalized.toString());
        if (normalized.isAbsolute()
                || normalizedPath.equals("..")
                || normalizedPath.startsWith("../")
                || normalizedPath.startsWith("/")) {
            return "";
        }
        return normalizedPath;
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
