package com.rush.rushaicodemother.orchestration.index;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rush.rushaicodemother.config.WorkspaceSemanticIndexCacheProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemException;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService.WorkspaceFileMetadata;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService.WorkspaceScan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * 工作区语义索引服务。
 */
@Slf4j
@Component
public class WorkspaceSemanticIndexService {

    private static final String SCHEMA_VERSION = "v3";
    private static final String INDEX_RELATIVE_PATH = ".ai-code-index/semantic-index.json";
    private static final long MAX_INDEX_FILE_BYTES = 64L * 1024 * 1024;
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

    private final Cache<String, CachedIndex> cache;
    private final ReentrantLock[] rebuildLocks;
    private final WorkspaceFileSystemService workspaceFileSystemService;

    public WorkspaceSemanticIndexService(WorkspaceFileSystemService workspaceFileSystemService) {
        this(workspaceFileSystemService, new WorkspaceSemanticIndexCacheProperties());
    }

    @Autowired
    public WorkspaceSemanticIndexService(
            WorkspaceFileSystemService workspaceFileSystemService,
            WorkspaceSemanticIndexCacheProperties properties) {
        this.workspaceFileSystemService = workspaceFileSystemService;
        WorkspaceSemanticIndexCacheProperties validated = properties == null
                ? new WorkspaceSemanticIndexCacheProperties() : properties;
        if (validated.getExpireAfterAccess() == null
                || validated.getExpireAfterAccess().isZero()
                || validated.getExpireAfterAccess().isNegative()) {
            throw new IllegalArgumentException("semantic index cache retention must be positive");
        }
        long minimumWorkspaceWeight = Math.max(
                1L,
                (validated.getMaximumIndexedFiles() + validated.getMaximumWorkspaces() - 1L)
                        / validated.getMaximumWorkspaces());
        this.cache = Caffeine.newBuilder()
                .maximumWeight(validated.getMaximumIndexedFiles())
                .weigher((String key, CachedIndex value) -> Math.max(
                        Math.toIntExact(Math.min(Integer.MAX_VALUE, minimumWorkspaceWeight)),
                        value.index().indexedFileCount()))
                .expireAfterAccess(validated.getExpireAfterAccess())
                .build();
        this.rebuildLocks = java.util.stream.IntStream.range(0, validated.getLockStripes())
                .mapToObj(ignored -> new ReentrantLock())
                .toArray(ReentrantLock[]::new);
    }

    /**
 * 加载{@code Or}构建。
 *
 * @param rootDir {@code rootDir} 对应的调用参数
 * @return {@code Or}构建
 */
    public WorkspaceSemanticIndex loadOrBuild(Path rootDir) {
        Path normalizedRoot = normalizeRoot(rootDir);
        String cacheKey = normalizedRoot.toString();
        ReentrantLock lock = rebuildLock(cacheKey);
        lock.lock();
        try {
            try {
                WorkspaceScan scan = workspaceFileSystemService.scanProject(normalizedRoot);
                String signature = computeWorkspaceSignature(scan);
                CachedIndex cachedIndex = cache.getIfPresent(cacheKey);
                if (cachedIndex != null && signature.equals(cachedIndex.signature())) {
                    return cachedIndex.index();
                }
                WorkspaceSemanticIndex loaded = readIndex(normalizedRoot);
                if (loaded != null
                        && SCHEMA_VERSION.equals(loaded.schemaVersion())
                        && signature.equals(loaded.workspaceSignature())) {
                    cache.put(cacheKey, new CachedIndex(signature, loaded));
                    return loaded;
                }
                WorkspaceSemanticIndex rebuilt;
                try {
                    rebuilt = buildIndex(scan, signature);
                } catch (WorkspaceFileSystemException exception) {
                    if (exception.reason() != WorkspaceFileSystemException.Reason.FILE_CHANGED) {
                        throw exception;
                    }
                    scan = workspaceFileSystemService.scanProject(normalizedRoot);
                    signature = computeWorkspaceSignature(scan);
                    rebuilt = buildIndex(scan, signature);
                }
                writeIndex(normalizedRoot, rebuilt);
                cache.put(cacheKey, new CachedIndex(signature, rebuilt));
                return rebuilt;
            } catch (Exception exception) {
                log.warn("构建工作区语义索引失败，rootDir: {}", normalizedRoot, LogExceptionSanitizer.sanitize(exception));
                WorkspaceSemanticIndex unavailable = emptyIndex(normalizedRoot);
                cache.invalidate(cacheKey);
                return unavailable;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
 * 返回数量{@code Indexable}文件。
 *
 * @param rootDir {@code rootDir} 对应的调用参数
 * @return 计算或处理后的数值结果
 */
    public int countIndexableFiles(Path rootDir) {
        return indexedFileCount(loadOrBuild(rootDir));
    }

    public int indexedFileCount(WorkspaceSemanticIndex index) {
        return index == null ? 0 : index.indexedFileCount();
    }

    /**
 * 返回数量{@code Indexed}{@code Symbols}。
 *
 * @param rootDir {@code rootDir} 对应的调用参数
 * @return 计算或处理后的数值结果
 */
    public int countIndexedSymbols(Path rootDir) {
        return indexedSymbolCount(loadOrBuild(rootDir));
    }

    /**
 * 返回{@code indexed}{@code Symbol}数量。
 *
 * @param index 索引
 * @return 计算或处理后的数值结果
 */
    public int indexedSymbolCount(WorkspaceSemanticIndex index) {
        if (index == null || index.entries() == null) {
            return 0;
        }
        return index.entries().stream()
                .map(WorkspaceSemanticIndexEntry::symbols)
                .mapToInt(symbols -> symbols == null ? 0 : symbols.size())
                .sum();
    }

    /**
 * 返回{@code suggest}文件。
 *
 * @param rootDir {@code rootDir} 对应的调用参数
 * @param query 查询
 * @param limit 资源上限
 * @return 工作区语义索引集合
 */
    public List<String> suggestFiles(Path rootDir, String query, int limit) {
        if (rootDir == null) {
            return List.of();
        }
        return suggestFilesFromSnapshot(loadOrBuild(rootDir), query, limit);
    }

    /**
 * 返回{@code suggest}文件{@code From}快照。
 *
 * @param index 索引
 * @param query 查询
 * @param limit 资源上限
 * @return 工作区语义索引集合
 */
    public List<String> suggestFilesFromSnapshot(WorkspaceSemanticIndex index, String query, int limit) {
        return searchSnapshot(index, query, Set.of(), limit).stream()
                .map(WorkspaceSemanticSearchHit::relativePath)
                .toList();
    }

    /**
 * 查找匹配的{@code Matching}文件。
 *
 * @param rootDir {@code rootDir} 对应的调用参数
 * @param keywords 待处理的 {@code keywords} 集合
 * @param limit 资源上限
 * @return {@code Matching}文件集合
 */
    public List<String> findMatchingFiles(Path rootDir, List<String> keywords, int limit) {
        if (rootDir == null) {
            return List.of();
        }
        return findMatchingFilesFromSnapshot(loadOrBuild(rootDir), keywords, limit);
    }

    /**
 * 查找匹配的{@code Matching}文件{@code From}快照。
 *
 * @param index 索引
 * @param keywords 待处理的 {@code keywords} 集合
 * @param limit 资源上限
 * @return {@code Matching}文件{@code From}快照集合
 */
    public List<String> findMatchingFilesFromSnapshot(WorkspaceSemanticIndex index,
                                                      List<String> keywords,
                                                      int limit) {
        if (CollUtil.isEmpty(keywords)) {
            return List.of();
        }
        String query = String.join(" ", keywords);
        return suggestFilesFromSnapshot(index, query, limit);
    }

    /**
 * 查找匹配的文件按{@code Symbol}。
 *
 * @param rootDir {@code rootDir} 对应的调用参数
 * @param symbol {@code symbol} 对应的调用参数
 * @param limit 资源上限
 * @return 文件按{@code Symbol}集合
 */
    public List<String> findFilesBySymbol(Path rootDir, String symbol, int limit) {
        if (rootDir == null || StrUtil.isBlank(symbol) || limit <= 0) {
            return List.of();
        }
        String normalizedSymbol = normalize(symbol);
        return loadOrBuild(rootDir).entries().stream()
                .filter(entry -> entry.symbols() != null
                        && entry.symbols().stream().anyMatch(item -> normalize(item).equals(normalizedSymbol)))
                .sorted(Comparator.comparing(WorkspaceSemanticIndexEntry::relativePath))
                .limit(limit)
                .map(WorkspaceSemanticIndexEntry::relativePath)
                .toList();
    }

    /**
 * 查找匹配的文件{@code Referencing}。
 *
 * @param rootDir {@code rootDir} 对应的调用参数
 * @param token 令牌
 * @param extensionFilter {@code extensionFilter} 对应的调用参数
 * @param limit 资源上限
 * @return 文件{@code Referencing}集合
 */
    public List<String> findFilesReferencing(Path rootDir, String token, Set<String> extensionFilter, int limit) {
        if (rootDir == null || StrUtil.isBlank(token) || limit <= 0) {
            return List.of();
        }
        String normalizedToken = normalize(token);
        Set<String> normalizedExtensions = normalizeExtensions(extensionFilter);
        return loadOrBuild(rootDir).entries().stream()
                .filter(entry -> normalizedExtensions.isEmpty() || normalizedExtensions.contains(entry.extension()))
                .filter(entry -> normalize(entry.searchableText()).contains(normalizedToken))
                .sorted(Comparator.comparing(WorkspaceSemanticIndexEntry::relativePath))
                .limit(limit)
                .map(WorkspaceSemanticIndexEntry::relativePath)
                .toList();
    }

    /**
 * 查找匹配的文件{@code Importing}。
 *
 * @param rootDir {@code rootDir} 对应的调用参数
 * @param importTarget 导入目标
 * @param limit 资源上限
 * @return 文件{@code Importing}集合
 */
    public List<String> findFilesImporting(Path rootDir, String importTarget, int limit) {
        if (rootDir == null || StrUtil.isBlank(importTarget) || limit <= 0) {
            return List.of();
        }
        String normalizedImportTarget = normalize(importTarget);
        return loadOrBuild(rootDir).entries().stream()
                .filter(entry -> Set.of("vue", "js", "ts", "jsx", "tsx").contains(entry.extension()))
                .filter(entry -> normalize(entry.contentExcerpt()).contains("from '" + normalizedImportTarget + "'")
                        || normalize(entry.contentExcerpt()).contains("from \"" + normalizedImportTarget + "\"")
                        || normalize(entry.contentExcerpt()).contains("import '" + normalizedImportTarget + "'")
                        || normalize(entry.contentExcerpt()).contains("import \"" + normalizedImportTarget + "\""))
                .sorted(Comparator.comparing(WorkspaceSemanticIndexEntry::relativePath))
                .limit(limit)
                .map(WorkspaceSemanticIndexEntry::relativePath)
                .toList();
    }

    /**
 * 返回{@code describe}文件。
 *
 * @param rootDir {@code rootDir} 对应的调用参数
 * @param relativePaths 待处理的 {@code relativePaths} 集合
 * @return 工作区语义索引集合
 */
    public List<WorkspaceSemanticSearchHit> describeFiles(Path rootDir, List<String> relativePaths) {
        if (rootDir == null) {
            return List.of();
        }
        return describeFilesFromSnapshot(loadOrBuild(rootDir), relativePaths);
    }

    /**
 * 返回{@code describe}文件{@code From}快照。
 *
 * @param index 索引
 * @param relativePaths 待处理的 {@code relativePaths} 集合
 * @return 工作区语义索引集合
 */
    public List<WorkspaceSemanticSearchHit> describeFilesFromSnapshot(WorkspaceSemanticIndex index,
                                                                      List<String> relativePaths) {
        if (index == null || CollUtil.isEmpty(relativePaths)) {
            return List.of();
        }
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
        refreshFilesIndex(rootDir, List.of(relativePath));
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
        refreshFilesIndex(rootDir, List.of(relativePath));
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
        Path normalizedRoot = normalizeRoot(rootDir);
        String cacheKey = normalizedRoot.toString();
        CachedIndex cachedIndex = cache.getIfPresent(cacheKey);
        if (cachedIndex == null) {
            return;
        }
        Set<String> selectedPaths = relativePaths.stream()
                .filter(StrUtil::isNotBlank)
                .map(path -> path.replace("\\", "/"))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (selectedPaths.isEmpty()) {
            return;
        }
        ReentrantLock lock = rebuildLock(cacheKey);
        lock.lock();
        try {
            CachedIndex current = cache.getIfPresent(cacheKey);
            if (current == null) {
                return;
            }
            List<WorkspaceSemanticIndexEntry> entries = new ArrayList<>(current.index().entries());
            entries.removeIf(entry -> selectedPaths.contains(entry.relativePath()));
            for (String selectedPath : selectedPaths) {
                if (!isIndexable(selectedPath)) {
                    continue;
                }
                try {
                    WorkspaceFileMetadata file = workspaceFileSystemService.resolveExistingFile(
                            normalizedRoot, selectedPath);
                    entries.add(buildEntry(normalizedRoot, file));
                } catch (WorkspaceFileSystemException missingFile) {
                    if (missingFile.reason() != WorkspaceFileSystemException.Reason.MISSING_FILE) {
                        throw missingFile;
                    }
                }
            }
            entries.sort(Comparator.comparing(WorkspaceSemanticIndexEntry::relativePath));
            String signature = computeWorkspaceSignature(normalizedRoot, entries);
            WorkspaceSemanticIndex updatedIndex = new WorkspaceSemanticIndex(
                    SCHEMA_VERSION,
                    normalizedRoot.toString(),
                    signature,
                    System.currentTimeMillis(),
                    entries.size(),
                    List.copyOf(entries)
            );
            cache.put(cacheKey, new CachedIndex(signature, updatedIndex));
            // 持久化快照留给下次全量构建；高频单文件编辑不放大为 O(项目文件数) 写入。
            log.debug("增量更新文件索引，paths: {}", selectedPaths);
        } catch (Exception exception) {
            log.warn("增量更新文件索引失败，rootDir: {}", normalizedRoot, LogExceptionSanitizer.sanitize(exception));
        } finally {
            lock.unlock();
        }
    }

    /** 终态或工作区回收时释放一个根目录的内存索引。 */
    public void invalidate(Path rootDir) {
        if (rootDir != null) {
            cache.invalidate(normalizeRoot(rootDir).toString());
        }
    }

    /** 释放某执行纪元目录下所有按项目类型建立的索引。 */
    public void invalidateUnder(Path rootDir) {
        if (rootDir == null) {
            return;
        }
        Path normalized = normalizeRoot(rootDir);
        cache.asMap().keySet().removeIf(key -> {
            try {
                return Path.of(key).toAbsolutePath().normalize().startsWith(normalized);
            } catch (RuntimeException invalidKey) {
                return true;
            }
        });
    }

    long estimatedCacheSize() {
        cache.cleanUp();
        return cache.estimatedSize();
    }

    WorkspaceSemanticIndex cachedSnapshot(Path rootDir) {
        if (rootDir == null) {
            return null;
        }
        CachedIndex cachedIndex = cache.getIfPresent(normalizeRoot(rootDir).toString());
        return cachedIndex == null ? null : cachedIndex.index();
    }

    private ReentrantLock rebuildLock(String cacheKey) {
        return rebuildLocks[Math.floorMod(cacheKey.hashCode(), rebuildLocks.length)];
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

    /**
 * 搜索匹配的工作区语义索引。
 *
 * @param rootDir {@code rootDir} 对应的调用参数
 * @param query 查询
 * @param extensionFilter {@code extensionFilter} 对应的调用参数
 * @param limit 资源上限
 * @return 工作区语义索引集合
 */
    public List<WorkspaceSemanticSearchHit> search(Path rootDir, String query, Set<String> extensionFilter, int limit) {
        if (rootDir == null || StrUtil.isBlank(query) || limit <= 0) {
            return List.of();
        }
        return searchSnapshot(loadOrBuild(rootDir), query, extensionFilter, limit);
    }

    /**
 * 搜索匹配的快照。
 *
 * @param index 索引
 * @param query 查询
 * @param extensionFilter {@code extensionFilter} 对应的调用参数
 * @param limit 资源上限
 * @return 快照集合
 */
    public List<WorkspaceSemanticSearchHit> searchSnapshot(WorkspaceSemanticIndex index,
                                                           String query,
                                                           Set<String> extensionFilter,
                                                           int limit) {
        if (index == null || StrUtil.isBlank(query) || limit <= 0) {
            return List.of();
        }
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

    /** 构建并返回索引。 */
    private WorkspaceSemanticIndex buildIndex(WorkspaceScan scan, String signature) throws IOException {
        List<WorkspaceSemanticIndexEntry> entries = new ArrayList<>();
        for (WorkspaceFileMetadata file : scan.files()) {
            if (!isIndexable(file.relativePath())) {
                continue;
            }
            entries.add(buildEntry(scan, file));
        }
        return new WorkspaceSemanticIndex(
                SCHEMA_VERSION,
                scan.root().toString(),
                signature,
                System.currentTimeMillis(),
                entries.size(),
                List.copyOf(entries)
        );
    }

    /** 构建并返回条目。 */
    private WorkspaceSemanticIndexEntry buildEntry(WorkspaceScan scan, WorkspaceFileMetadata file) throws IOException {
        return buildEntry(scan.root(), file);
    }

    private WorkspaceSemanticIndexEntry buildEntry(Path root, WorkspaceFileMetadata file) throws IOException {
        String relativePath = file.relativePath();
        String fileName = file.fileName();
        String extension = normalizeExtension(FileUtil.extName(fileName));
        String contentExcerpt = "";
        List<String> symbols = List.of();
        if (file.size() <= 512 * 1024) {
            try {
                String content = workspaceFileSystemService.readUtf8(root, file, 512 * 1024L);
                contentExcerpt = truncate(normalizeContent(content));
                symbols = extractSymbols(content, fileName);
            } catch (WorkspaceFileSystemException exception) {
                if (exception.reason() == WorkspaceFileSystemException.Reason.FILE_CHANGED) {
                    throw exception;
                }
                log.debug("读取索引内容失败，relativePath: {}, reason: {}", relativePath, exception.reason());
            }
        }
        List<String> terms = extractTerms(relativePath + "\n" + fileName + "\n" + contentExcerpt + "\n" + String.join(" ", symbols));
        String searchableText = normalize(relativePath + "\n" + fileName + "\n" + contentExcerpt + "\n"
                + String.join(" ", terms) + "\n" + String.join(" ", symbols));
        return new WorkspaceSemanticIndexEntry(
                relativePath,
                fileName,
                extension,
                file.size(),
                file.lastModifiedTime(),
                searchableText,
                contentExcerpt,
                List.copyOf(terms),
                List.copyOf(symbols)
        );
    }

    /** 返回{@code score}条目。 */
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
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
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

    /** 写入索引。 */
    private void writeIndex(Path rootDir, WorkspaceSemanticIndex index) {
        try {
            JSONObject payload = toJson(index);
            workspaceFileSystemService.writeUtf8Atomically(rootDir, INDEX_RELATIVE_PATH, payload.toStringPretty());
        } catch (IOException exception) {
            log.debug("工作区索引不可写，跳过持久化，rootDir: {}", rootDir, LogExceptionSanitizer.sanitize(exception));
        }
    }

    /** 读取索引。 */
    private WorkspaceSemanticIndex readIndex(Path rootDir) {
        try {
            String persistedIndex = workspaceFileSystemService
                    .readOptionalUtf8(rootDir, INDEX_RELATIVE_PATH, MAX_INDEX_FILE_BYTES)
                    .orElse(null);
            if (StrUtil.isBlank(persistedIndex)) {
                return null;
            }
            JSONObject payload = JSONUtil.parseObj(persistedIndex);
            if (payload == null || payload.isEmpty()) {
                return null;
            }
            return fromJson(payload);
        } catch (Exception exception) {
            log.debug("读取工作区语义索引失败，rootDir: {}", rootDir, LogExceptionSanitizer.sanitize(exception));
            return null;
        }
    }

    /** 将当前对象转换为{@code Json}。 */
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

    /** 根据输入数据创建当前对象。 */
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

    /** 读取{@code String}列表。 */
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

    /** 计算工作区签名。 */
    private String computeWorkspaceSignature(WorkspaceScan scan) {
        List<WorkspaceFileMetadata> eligibleEntries = scan.files().stream()
                .filter(file -> isIndexable(file.relativePath()))
                .toList();
        StringBuilder builder = new StringBuilder(scan.root().toString())
                .append('|')
                .append(eligibleEntries.size());
        for (WorkspaceFileMetadata file : eligibleEntries) {
            builder.append('\n')
                    .append(file.relativePath())
                    .append('|')
                    .append(file.size())
                    .append('|')
                    .append(file.lastModifiedTime());
        }
        return DigestUtil.sha256Hex(builder.toString());
    }

    private String computeWorkspaceSignature(Path root, List<WorkspaceSemanticIndexEntry> entries) {
        StringBuilder builder = new StringBuilder(root.toString())
                .append('|')
                .append(entries.size());
        for (WorkspaceSemanticIndexEntry entry : entries) {
            builder.append('\n')
                    .append(entry.relativePath())
                    .append('|')
                    .append(entry.size())
                    .append('|')
                    .append(entry.lastModified());
        }
        return DigestUtil.sha256Hex(builder.toString());
    }

    private Path normalizeRoot(Path rootDir) {
        if (rootDir == null) {
            throw new IllegalArgumentException("工作区根目录不能为空");
        }
        return rootDir.toAbsolutePath().normalize();
    }

    private WorkspaceSemanticIndex emptyIndex(Path rootDir) {
        return new WorkspaceSemanticIndex(
                SCHEMA_VERSION,
                rootDir.toString(),
                DigestUtil.sha256Hex(rootDir.toString()),
                System.currentTimeMillis(),
                0,
                List.of()
        );
    }

    /** 判断{@code Indexable}是否满足约束。 */
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

    /** 从输入中提取{@code Terms}。 */
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

    /** 从输入中提取{@code Symbols}。 */
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

    /** 规范化{@code Extensions}。 */
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
