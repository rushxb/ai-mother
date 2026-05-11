package com.yupi.yuaicodemother.orchestration.index;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.yupi.yuaicodemother.ai.tools.ProjectWorkspaceSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    private static final String SCHEMA_VERSION = "v1";
    private static final String INDEX_DIRECTORY_NAME = ".ai-code-index";
    private static final String INDEX_FILE_NAME = "semantic-index.json";
    private static final int MAX_INDEXED_CONTENT_CHARS = 6000;
    private static final int MAX_TERMS_PER_FILE = 40;
    private static final int MAX_SEARCH_RESULTS = 20;
    private static final Set<String> INDEXABLE_EXTENSIONS = Set.of(
            "vue", "js", "ts", "jsx", "tsx", "css", "scss", "less", "json", "svg",
            "md", "html", "go", "sql", "java", "xml", "yml", "yaml", "txt"
    );
    private static final Pattern TERM_PATTERN = Pattern.compile("[\\p{L}\\p{N}_-]{2,}");

    private final ConcurrentMap<String, CachedIndex> cache = new ConcurrentHashMap<>();

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
        if (loaded != null && signature.equals(loaded.workspaceSignature())) {
            cache.put(cacheKey, new CachedIndex(signature, loaded));
            return loaded;
        }

        WorkspaceSemanticIndex rebuilt = buildIndex(normalizedRoot, signature);
        writeIndex(indexFile, rebuilt);
        cache.put(cacheKey, new CachedIndex(signature, rebuilt));
        return rebuilt;
    }

    public int countIndexableFiles(Path rootDir) {
        return loadOrBuild(rootDir).indexedFileCount();
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
                        scored.preview()
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
        if (size <= 512 * 1024) {
            try {
                String content = FileUtil.readString(absolutePath.toFile(), StandardCharsets.UTF_8);
                contentExcerpt = truncate(normalizeContent(content));
            } catch (Exception e) {
                log.debug("读取索引内容失败，path: {}", absolutePath, e);
            }
        }
        List<String> terms = extractTerms(relativePath + "\n" + fileName + "\n" + contentExcerpt);
        String searchableText = normalize(relativePath + "\n" + fileName + "\n" + contentExcerpt + "\n" + String.join(" ", terms));
        return new WorkspaceSemanticIndexEntry(
                relativePath,
                fileName,
                extension,
                size,
                lastModified,
                searchableText,
                contentExcerpt,
                List.copyOf(terms)
        );
    }

    private ScoredHit scoreEntry(WorkspaceSemanticIndexEntry entry, String query, List<String> queryTerms) {
        int score = 0;
        String relativePath = normalize(entry.relativePath());
        String fileName = normalize(entry.fileName());
        String searchableText = normalize(entry.searchableText());

        String matchType = "symbol";
        if (relativePath.contains(query)) {
            score += 120;
            matchType = "path";
        }
        if (fileName.contains(query)) {
            score += 100;
            matchType = "file_name";
        }
        if (!entry.contentExcerpt().isBlank() && entry.contentExcerpt().toLowerCase(Locale.ROOT).contains(query)) {
            score += 80;
            if (!"path".equals(matchType) && !"file_name".equals(matchType)) {
                matchType = "content";
            }
        }
        for (String term : queryTerms) {
            if (relativePath.contains(term)) {
                score += 25;
            }
            if (fileName.contains(term)) {
                score += 20;
            }
            if (searchableText.contains(term)) {
                score += 10;
            }
            if (entry.terms().contains(term)) {
                score += 15;
            }
        }
        String preview = StrUtil.blankToDefault(entry.contentExcerpt(), entry.relativePath());
        if (preview.length() > 260) {
            preview = preview.substring(0, 260).trim() + "...";
        }
        return new ScoredHit(entry.relativePath(), entry.fileName(), matchType, score, preview);
    }

    private void writeIndex(Path indexFile, WorkspaceSemanticIndex index) {
        try {
            Files.createDirectories(indexFile.getParent());
            JSONObject payload = toJson(index);
            FileUtil.writeString(payload.toStringPretty(), indexFile.toFile(), StandardCharsets.UTF_8);
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
                        readStringList(entryObject.getJSONArray("terms"))
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

    private record ScoredHit(String relativePath, String fileName, String matchType, int score, String preview) {
    }
}
