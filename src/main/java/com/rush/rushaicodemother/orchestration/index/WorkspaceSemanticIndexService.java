package com.rush.rushaicodemother.orchestration.index;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.ai.tools.ProjectWorkspaceSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * 工作区语义索引服务。
 */
@Slf4j
@Component
public class WorkspaceSemanticIndexService {

    private static final String SCHEMA_VERSION = "v2";
    private static final String INDEX_DIRECTORY_NAME = ".ai-code-index";
    private static final String INDEX_FILE_NAME = "semantic-index.json";
    private static final int MAX_INDEXED_CONTENT_CHARS = 6000;
    private static final int MAX_TERMS_PER_FILE = 40;
    private static final int MAX_SYMBOLS_PER_FILE = 80;
    private static final int MAX_SEARCH_RESULTS = 20;
    private static final Set<String> INDEXABLE_EXTENSIONS = Set.of(
            "vue", "js", "ts", "jsx", "tsx", "css", "scss", "less", "json", "svg",
            "md", "html", "go", "sql", "java", "xml", "yml", "yaml", "txt"
    );
    private static final Pattern TERM_PATTERN = Pattern.compile("[\\p{L}\\p{N}_-]{2,}");
    private static final List<Pattern> SYMBOL_PATTERNS = List.of(
            Pattern.compile("\\b(?:export\\s+default\\s+)?(?:export\\s+)?(?:async\\s+)?(?:function|class|interface|type|enum|const|let|var)\\s+([A-Za-z_$][\\w$-]*)"),
            Pattern.compile("\\b(?:const|let|var)\\s+([A-Za-z_$][\\w$-]*)\\s*=\\s*(?:async\\s*)?\\([^\\n)]*\\)\\s*=>"),
            Pattern.compile("\\b(?:public|protected|private|static|final|synchronized|abstract|default|\\s)+[\\w<>\\[\\], ?]+\\s+([A-Za-z_$][\\w$]*)\\s*\\([^;{}]*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\{"),
            Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)"),
            Pattern.compile("\\b(?:func|type)\\s+(?:\\([^)]*\\)\\s*)?([A-Za-z_][\\w]*)"),
            Pattern.compile("\\bname\\s*:\\s*['\"]([A-Za-z_$][\\w$-]*)['\"]")
    );

    private final ConcurrentMap<String, CachedIndex> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> rebuildLocks = new ConcurrentHashMap<>();

    public WorkspaceSemanticIndex loadOrBuild(Path rootDir) {
        Path normalizedRoot = normalizeRoot(rootDir);
        String cacheKey = normalizedRoot.toString();
        String signature = computeWorkspaceSignature(normalizedRoot);
        CachedIndex cachedIndex = cache.get(cacheKey);
        if (cachedIndex != null && signature.equals(cachedIndex.signature())) {
            return cachedIndex.index();
        }

        Path indexFile = resolveIndexFile(normalizedRoot);
        WorkspaceSemanticIndex loaded = readIndex(indexFile);
        if (loaded != null && SCHEMA_VERSION.equals(loaded.schemaVersion()) && signature.equals(loaded.workspaceSignature())) {
            cache.put(cacheKey, new CachedIndex(signature, loaded));
            return loaded;
        }

        Object lock = rebuildLocks.computeIfAbsent(cacheKey, unused -> new Object());
        synchronized (lock) {
            CachedIndex refreshedCache = cache.get(cacheKey);
            if (refreshedCache != null && signature.equals(refreshedCache.signature())) {
                return refreshedCache.index();
            }
            WorkspaceSemanticIndex refreshedLoaded = readIndex(indexFile);
            if (refreshedLoaded != null
                    && SCHEMA_VERSION.equals(refreshedLoaded.schemaVersion())
                    && signature.equals(refreshedLoaded.workspaceSignature())) {
                cache.put(cacheKey, new CachedIndex(signature, refreshedLoaded));
                return refreshedLoaded;
            }
            WorkspaceSemanticIndex rebuilt = buildIndex(normalizedRoot, signature);
            writeIndex(indexFile, rebuilt);
            cache.put(cacheKey, new CachedIndex(signature, rebuilt));
            return rebuilt;
        }
    }

    public int countIndexableFiles(Path rootDir) {
        return loadOrBuild(rootDir).indexedFileCount();
    }

    public int countIndexedSymbols(Path rootDir) {
        return loadOrBuild(rootDir).entries().stream()
                .map(WorkspaceSemanticIndexEntry::symbols)
                .mapToInt(symbols -> symbols == null ? 0 : symbols.size())
                .sum();
    }

    public List<String> suggestFiles(Path rootDir, String query, int limit) {
        return search(rootDir, query, Set.of(), limit).stream()
                .map(WorkspaceSemanticSearchHit::relativePath)
                .toList();
    }

    public List<String> findMatchingFiles(Path rootDir, List<String> keywords, int limit) {
        if (CollUtil.isEmpty(keywords)) {
            return List.of();
        }
        String query = String.join(" ", keywords);
        return suggestFiles(rootDir, query, limit);
    }

    public List<WorkspaceSemanticSearchHit> describeFiles(Path rootDir, List<String> relativePaths) {
        if (rootDir == null || CollUtil.isEmpty(relativePaths)) {
            return List.of();
        }
        WorkspaceSemanticIndex index = loadOrBuild(rootDir);
        Set<String> normalizedPaths = relativePaths.stream()
                .filter(StrUtil::isNotBlank)
                .map(path -> path.replace("\\", "/"))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return index.entries().stream()
                .filter(entry -> normalizedPaths.contains(entry.relativePath()))
                .map(entry -> new WorkspaceSemanticSearchHit(
                        entry.relativePath(),
                        entry.fileName(),
                        "selected_file",
                        0,
                        buildPreview(entry),
                        "semantic_index",
                        List.of(),
                        entry.symbols() == null ? List.of() : entry.symbols().stream().limit(8).toList()
                ))
                .toList();
    }

    /**
     * 增量更新索引：修改指定文件的索引条目。
     * 只刷新指定文件，不重建整个索引。
     *
     * @param rootDir      工作区根目录
     * @param relativePath 相对路径
     */
    public void refreshFileIndex(Path rootDir, String relativePath) {
        if (rootDir == null || StrUtil.isBlank(relativePath)) {
            return;
        }
        Path normalizedRoot = normalizeRoot(rootDir);
        String normalizedRelativePath = relativePath.replace("\\", "/");
        if (!isIndexable(normalizedRelativePath)) {
            return;
        }
        Path absolutePath = normalizedRoot.resolve(normalizedRelativePath);
        if (!Files.isRegularFile(absolutePath)) {
            return;
        }
        String cacheKey = normalizedRoot.toString();
        CachedIndex cachedIndex = cache.get(cacheKey);
        if (cachedIndex == null) {
            return;
        }
        WorkspaceSemanticIndex index = cachedIndex.index();
        List<WorkspaceSemanticIndexEntry> entries = new ArrayList<>(index.entries());
        entries.removeIf(entry -> normalizedRelativePath.equals(entry.relativePath()));
        try {
            entries.add(buildEntry(normalizedRelativePath, absolutePath));
        } catch (IOException e) {
            log.warn("构建文件索引失败，path: {}", absolutePath, e);
            return;
        }
        WorkspaceSemanticIndex updatedIndex = new WorkspaceSemanticIndex(
                index.schemaVersion(),
                index.rootPath(),
                index.workspaceSignature(),
                System.currentTimeMillis(),
                entries.size(),
                List.copyOf(entries)
        );
        cache.put(cacheKey, new CachedIndex(index.workspaceSignature(), updatedIndex));
        Path indexFile = resolveIndexFile(normalizedRoot);
        writeIndex(indexFile, updatedIndex);
        log.debug("增量更新文件索引，path: {}", normalizedRelativePath);
    }

    /**
     * 增量更新索引：删除指定文件的索引条目。
     *
     * @param rootDir      工作区根目录
     * @param relativePath 相对路径
     */
    public void removeFileIndex(Path rootDir, String relativePath) {
        if (rootDir == null || StrUtil.isBlank(relativePath)) {
            return;
        }
        Path normalizedRoot = normalizeRoot(rootDir);
        String normalizedRelativePath = relativePath.replace("\\", "/");
        String cacheKey = normalizedRoot.toString();
        CachedIndex cachedIndex = cache.get(cacheKey);
        if (cachedIndex == null) {
            return;
        }
        WorkspaceSemanticIndex index = cachedIndex.index();
        List<WorkspaceSemanticIndexEntry> entries = new ArrayList<>(index.entries());
        boolean removed = entries.removeIf(entry -> normalizedRelativePath.equals(entry.relativePath()));
        if (!removed) {
            return;
        }
        WorkspaceSemanticIndex updatedIndex = new WorkspaceSemanticIndex(
                index.schemaVersion(),
                index.rootPath(),
                index.workspaceSignature(),
                System.currentTimeMillis(),
                entries.size(),
                List.copyOf(entries)
        );
        cache.put(cacheKey, new CachedIndex(index.workspaceSignature(), updatedIndex));
        Path indexFile = resolveIndexFile(normalizedRoot);
        writeIndex(indexFile, updatedIndex);
        log.debug("删除文件索引，path: {}", normalizedRelativePath);
    }

    /**
     * 增量更新索引：批量刷新指定文件的索引条目。
     *
     * @param rootDir       工作区根目录
     * @param relativePaths 相对路径列表
     */
    public void refreshFilesIndex(Path rootDir, List<String> relativePaths) {
        if (rootDir == null || CollUtil.isEmpty(relativePaths)) {
            return;
        }
        for (String relativePath : relativePaths) {
            refreshFileIndex(rootDir, relativePath);
        }
    }

    /**
     * 增量更新索引：添加新文件的索引条目。
     *
     * @param rootDir      工作区根目录
     * @param relativePath 相对路径
     */
    public void addFileIndex(Path rootDir, String relativePath) {
        refreshFileIndex(rootDir, relativePath);
    }

    public List<WorkspaceSemanticSearchHit> search(Path rootDir, String query, Set<String> extensionFilter, int limit) {
        if (rootDir == null || StrUtil.isBlank(query) || limit <= 0) {
            return List.of();
        }
        WorkspaceSemanticIndex index = loadOrBuild(rootDir);
        String normalizedQuery = normalize(query);
        List<String> queryTerms = extractTerms(normalizedQuery);
        Set<String> normalizedExtensions = normalizeExtensions(extensionFilter);
        return index.entries().stream()
                .filter(entry -> normalizedExtensions.isEmpty() || normalizedExtensions.contains(entry.extension()))
                .map(entry -> scoreEntry(entry, normalizedQuery, queryTerms))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(ScoredHit::score).reversed()
                        .thenComparing(ScoredHit::relativePath))
                .limit(limit)
                .map(scored -> new WorkspaceSemanticSearchHit(
                        scored.relativePath(),
                        scored.fileName(),
                        scored.matchType(),
                        scored.score(),
                        scored.preview(),
                        scored.recallSource(),
                        scored.matchedTerms(),
                        scored.matchedSymbols()
                ))
                .toList();
    }

    private WorkspaceSemanticIndex buildIndex(Path rootDir, String signature) {
        try {
            List<Path> relativeFiles = ProjectWorkspaceSupport.listProjectFiles(rootDir);
            List<WorkspaceSemanticIndexEntry> entries = new ArrayList<>();
            for (Path relativePath : relativeFiles) {
                String normalizedRelativePath = normalizeRelativePath(relativePath);
                if (!isIndexable(normalizedRelativePath)) {
                    continue;
                }
                Path absolutePath = rootDir.resolve(relativePath);
                if (!Files.isRegularFile(absolutePath)) {
                    continue;
                }
                entries.add(buildEntry(normalizedRelativePath, absolutePath));
            }
            return new WorkspaceSemanticIndex(
                    SCHEMA_VERSION,
                    rootDir.toString(),
                    signature,
                    System.currentTimeMillis(),
                    entries.size(),
                    List.copyOf(entries)
            );
        } catch (Exception e) {
            log.warn("构建工作区语义索引失败，rootDir: {}", rootDir, e);
            return new WorkspaceSemanticIndex(
                    SCHEMA_VERSION,
                    rootDir.toString(),
                    signature,
                    System.currentTimeMillis(),
                    0,
                    List.of()
            );
        }
    }

    private WorkspaceSemanticIndexEntry buildEntry(String relativePath, Path absolutePath) throws IOException {
        long size = Files.size(absolutePath);
        long lastModified = Files.getLastModifiedTime(absolutePath).toMillis();
        String fileName = absolutePath.getFileName().toString();
        String extension = normalizeExtension(FileUtil.extName(fileName));
        String contentExcerpt = "";
        List<String> symbols = List.of();
        if (size <= 512 * 1024) {
            try {
                String content = FileUtil.readString(absolutePath.toFile(), StandardCharsets.UTF_8);
                contentExcerpt = truncate(normalizeContent(content));
                symbols = extractSymbols(content, fileName);
            } catch (Exception e) {
                log.debug("读取索引内容失败，path: {}", absolutePath, e);
            }
        }
        List<String> terms = extractTerms(relativePath + "\n" + fileName + "\n" + contentExcerpt + "\n" + String.join(" ", symbols));
        String searchableText = normalize(relativePath + "\n" + fileName + "\n" + contentExcerpt + "\n"
                + String.join(" ", terms) + "\n" + String.join(" ", symbols));
        return new WorkspaceSemanticIndexEntry(
                relativePath,
                fileName,
                extension,
                size,
                lastModified,
                searchableText,
                contentExcerpt,
                List.copyOf(terms),
                List.copyOf(symbols)
        );
    }

    private ScoredHit scoreEntry(WorkspaceSemanticIndexEntry entry, String query, List<String> queryTerms) {
        int score = 0;
        String relativePath = normalize(entry.relativePath());
        String fileName = normalize(entry.fileName());
        String searchableText = normalize(entry.searchableText());
        List<String> symbols = entry.symbols() == null ? List.of() : entry.symbols();
        String normalizedSymbols = normalize(String.join(" ", symbols));
        LinkedHashSet<String> matchedTerms = new LinkedHashSet<>();
        LinkedHashSet<String> matchedSymbols = new LinkedHashSet<>();

        String matchType = "content";
        if (relativePath.contains(query)) {
            score += 120;
            matchType = "path";
            matchedTerms.add(query);
        }
        if (fileName.contains(query)) {
            score += 100;
            matchType = "file_name";
            matchedTerms.add(query);
        }
        if (!normalizedSymbols.isBlank() && normalizedSymbols.contains(query)) {
            score += 140;
            matchType = "symbol";
            matchedTerms.add(query);
        }
        if (!entry.contentExcerpt().isBlank() && entry.contentExcerpt().toLowerCase(Locale.ROOT).contains(query)) {
            score += 80;
            if (!"path".equals(matchType) && !"file_name".equals(matchType) && !"symbol".equals(matchType)) {
                matchType = "content";
            }
            matchedTerms.add(query);
        }
        for (String term : queryTerms) {
            if (relativePath.contains(term)) {
                score += 25;
                matchedTerms.add(term);
            }
            if (fileName.contains(term)) {
                score += 20;
                matchedTerms.add(term);
            }
            if (searchableText.contains(term)) {
                score += 10;
                matchedTerms.add(term);
            }
            if (entry.terms().contains(term)) {
                score += 15;
                matchedTerms.add(term);
            }
            List<String> symbolMatches = symbols.stream()
                    .filter(symbol -> normalize(symbol).contains(term))
                    .limit(5)
                    .toList();
            if (!symbolMatches.isEmpty()) {
                score += 35 * symbolMatches.size();
                matchType = "symbol";
                matchedTerms.add(term);
                matchedSymbols.addAll(symbolMatches);
            }
        }
        String preview = StrUtil.blankToDefault(entry.contentExcerpt(), entry.relativePath());
        preview = limitPreview(preview);
        return new ScoredHit(
                entry.relativePath(),
                entry.fileName(),
                matchType,
                score,
                preview,
                "semantic_index",
                List.copyOf(matchedTerms),
                List.copyOf(matchedSymbols)
        );
    }

    private String buildPreview(WorkspaceSemanticIndexEntry entry) {
        return limitPreview(StrUtil.blankToDefault(entry.contentExcerpt(), entry.relativePath()));
    }

    private String limitPreview(String preview) {
        if (preview != null && preview.length() > 260) {
            return preview.substring(0, 260).trim() + "...";
        }
        return StrUtil.blankToDefault(preview, "");
    }

    private void writeIndex(Path indexFile, WorkspaceSemanticIndex index) {
        try {
            Files.createDirectories(indexFile.getParent());
            JSONObject payload = toJson(index);
            FileUtil.writeString(payload.toStringPretty(), indexFile.toFile(), StandardCharsets.UTF_8);
        } catch (AccessDeniedException e) {
            log.debug("工作区索引目录不可写，跳过持久化，indexFile: {}", indexFile, e);
        } catch (Exception e) {
            log.warn("写入工作区语义索引失败，indexFile: {}", indexFile, e);
        }
    }

    private WorkspaceSemanticIndex readIndex(Path indexFile) {
        if (indexFile == null || !Files.exists(indexFile)) {
            return null;
        }
        try {
            JSONObject payload = JSONUtil.readJSONObject(indexFile.toFile(), StandardCharsets.UTF_8);
            if (payload == null || payload.isEmpty()) {
                return null;
            }
            return fromJson(payload);
        } catch (Exception e) {
            log.debug("读取工作区语义索引失败，indexFile: {}", indexFile, e);
            return null;
        }
    }

    private JSONObject toJson(WorkspaceSemanticIndex index) {
        JSONObject payload = new JSONObject();
        payload.set("schemaVersion", index.schemaVersion());
        payload.set("rootPath", index.rootPath());
        payload.set("workspaceSignature", index.workspaceSignature());
        payload.set("indexedAt", index.indexedAt());
        payload.set("indexedFileCount", index.indexedFileCount());
        JSONArray entries = new JSONArray();
        for (WorkspaceSemanticIndexEntry entry : index.entries()) {
            JSONObject item = new JSONObject();
            item.set("relativePath", entry.relativePath());
            item.set("fileName", entry.fileName());
            item.set("extension", entry.extension());
            item.set("size", entry.size());
            item.set("lastModified", entry.lastModified());
            item.set("searchableText", entry.searchableText());
            item.set("contentExcerpt", entry.contentExcerpt());
            item.set("terms", new JSONArray(entry.terms()));
            item.set("symbols", new JSONArray(entry.symbols()));
            entries.add(item);
        }
        payload.set("entries", entries);
        return payload;
    }

    private WorkspaceSemanticIndex fromJson(JSONObject payload) {
        JSONArray entriesArray = payload.getJSONArray("entries");
        List<WorkspaceSemanticIndexEntry> entries = new ArrayList<>();
        if (entriesArray != null) {
            for (Object item : entriesArray) {
                if (!(item instanceof JSONObject entryObject)) {
                    continue;
                }
                entries.add(new WorkspaceSemanticIndexEntry(
                        entryObject.getStr("relativePath"),
                        entryObject.getStr("fileName"),
                        entryObject.getStr("extension"),
                        entryObject.getLong("size", 0L),
                        entryObject.getLong("lastModified", 0L),
                        entryObject.getStr("searchableText"),
                        entryObject.getStr("contentExcerpt"),
                        readStringList(entryObject.getJSONArray("terms")),
                        readStringList(entryObject.getJSONArray("symbols"))
                ));
            }
        }
        return new WorkspaceSemanticIndex(
                payload.getStr("schemaVersion", SCHEMA_VERSION),
                payload.getStr("rootPath"),
                payload.getStr("workspaceSignature"),
                payload.getLong("indexedAt", 0L),
                payload.getInt("indexedFileCount", entries.size()),
                List.copyOf(entries)
        );
    }

    private List<String> readStringList(JSONArray array) {
        if (array == null || array.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object value : array) {
            if (value != null && StrUtil.isNotBlank(String.valueOf(value))) {
                values.add(String.valueOf(value));
            }
        }
        return List.copyOf(values);
    }

    private String computeWorkspaceSignature(Path rootDir) {
        try {
            List<Path> files = ProjectWorkspaceSupport.listProjectFiles(rootDir);
            StringBuilder builder = new StringBuilder(rootDir.toString()).append('|').append(files.size());
            for (Path relativePath : files) {
                Path absolutePath = rootDir.resolve(relativePath);
                if (!Files.isRegularFile(absolutePath)) {
                    continue;
                }
                builder.append('\n')
                        .append(normalizeRelativePath(relativePath))
                        .append('|')
                        .append(Files.size(absolutePath))
                        .append('|')
                        .append(Files.getLastModifiedTime(absolutePath).toMillis());
            }
            return DigestUtil.sha256Hex(builder.toString());
        } catch (Exception e) {
            log.debug("计算工作区签名失败，rootDir: {}", rootDir, e);
            return DigestUtil.sha256Hex(rootDir.toString());
        }
    }

    private Path normalizeRoot(Path rootDir) {
        if (rootDir == null) {
            throw new IllegalArgumentException("工作区根目录不能为空");
        }
        return rootDir.toAbsolutePath().normalize();
    }

    private Path resolveIndexFile(Path rootDir) {
        return rootDir.resolve(INDEX_DIRECTORY_NAME).resolve(INDEX_FILE_NAME);
    }

    private boolean isIndexable(String relativePath) {
        if (StrUtil.isBlank(relativePath)) {
            return false;
        }
        String normalized = relativePath.replace("\\", "/");
        String extension = normalizeExtension(FileUtil.extName(normalized));
        if (normalized.startsWith("src/") || normalized.startsWith("public/") || normalized.startsWith("backend/")) {
            return INDEXABLE_EXTENSIONS.contains(extension);
        }
        return Set.of(
                "package.json",
                "index.html",
                "vite.config.js",
                "vite.config.ts",
                "tsconfig.json",
                "tsconfig.app.json",
                "pnpm-workspace.yaml"
        ).contains(normalized);
    }

    private String normalizeRelativePath(Path relativePath) {
        return relativePath == null ? "" : relativePath.toString().replace("\\", "/");
    }

    private String normalizeExtension(String extension) {
        return StrUtil.blankToDefault(extension, "").toLowerCase(Locale.ROOT);
    }

    private String normalizeContent(String content) {
        if (content == null) {
            return "";
        }
        return content.replace("\r", "\n").replaceAll("[\\t ]+", " ").trim();
    }

    private String truncate(String value) {
        if (StrUtil.isBlank(value) || value.length() <= MAX_INDEXED_CONTENT_CHARS) {
            return StrUtil.blankToDefault(value, "");
        }
        return value.substring(0, MAX_INDEXED_CONTENT_CHARS).trim();
    }

    private String normalize(String value) {
        return StrUtil.blankToDefault(value, "").toLowerCase(Locale.ROOT).replace('\\', '/').trim();
    }

    private List<String> extractTerms(String value) {
        if (StrUtil.isBlank(value)) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        java.util.regex.Matcher matcher = TERM_PATTERN.matcher(normalize(value));
        while (matcher.find() && terms.size() < MAX_TERMS_PER_FILE) {
            String term = matcher.group();
            if (StrUtil.isNotBlank(term)) {
                terms.add(term.toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(terms);
    }

    private List<String> extractSymbols(String content, String fileName) {
        LinkedHashSet<String> symbols = new LinkedHashSet<>();
        String mainName = FileUtil.mainName(StrUtil.blankToDefault(fileName, ""));
        if (StrUtil.isNotBlank(mainName) && !"index".equalsIgnoreCase(mainName)) {
            symbols.add(mainName);
        }
        if (StrUtil.isBlank(content)) {
            return List.copyOf(symbols);
        }
        for (Pattern pattern : SYMBOL_PATTERNS) {
            java.util.regex.Matcher matcher = pattern.matcher(content);
            while (matcher.find() && symbols.size() < MAX_SYMBOLS_PER_FILE) {
                String symbol = matcher.group(1);
                if (StrUtil.isNotBlank(symbol)) {
                    symbols.add(symbol.trim());
                }
            }
        }
        return List.copyOf(symbols);
    }

    private Set<String> normalizeExtensions(Set<String> extensionFilter) {
        if (extensionFilter == null || extensionFilter.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String extension : extensionFilter) {
            if (StrUtil.isNotBlank(extension)) {
                normalized.add(extension.replace(".", "").trim().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(normalized);
    }

    private record CachedIndex(String signature, WorkspaceSemanticIndex index) {
    }

    private record ScoredHit(String relativePath,
                             String fileName,
                             String matchType,
                             int score,
                             String preview,
                             String recallSource,
                             List<String> matchedTerms,
                             List<String> matchedSymbols) {
    }
}
