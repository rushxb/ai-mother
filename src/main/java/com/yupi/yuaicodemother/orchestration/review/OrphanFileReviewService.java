package com.yupi.yuaicodemother.orchestration.review;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaicodemother.orchestration.artifact.ChangePlan;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

@Component
public class OrphanFileReviewService {

    private static final Set<String> REVIEW_EXTENSIONS = Set.of("vue", "js", "ts", "css", "scss", "json");

    public OrphanFileReviewResult review(Path projectRoot, ChangePlan changePlan) {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) {
            return new OrphanFileReviewResult("skipped", List.of(), List.of(), List.of(), "项目目录不存在");
        }
        try {
            List<Path> files;
            try (Stream<Path> stream = Files.walk(projectRoot.resolve("src"), 8)) {
                files = stream.filter(Files::isRegularFile)
                        .filter(this::isReviewFile)
                        .toList();
            }
            String importCorpus = buildImportCorpus(projectRoot, files);
            List<String> orphanCandidates = new ArrayList<>();
            List<String> reasons = new ArrayList<>();
            List<String> deleteAllowed = new ArrayList<>();
            for (Path file : files) {
                String relative = projectRoot.relativize(file).toString().replace("\\", "/");
                String fileName = file.getFileName().toString();
                String lowerName = fileName.toLowerCase(Locale.ROOT);
                if (!looksLikeTemplateResidual(lowerName)) {
                    continue;
                }
                String baseName = stripExtension(fileName);
                if (importCorpus.contains(baseName) || importCorpus.contains(relative)) {
                    continue;
                }
                orphanCandidates.add(relative);
                reasons.add(relative + ": 文件名疑似旧模板残留且未被 import/router 引用");
                if (changePlan != null && changePlan.deleteFiles().contains(relative)) {
                    deleteAllowed.add(relative);
                }
            }
            String status = orphanCandidates.isEmpty() ? "passed" : "warning";
            String summary = orphanCandidates.isEmpty() ? "未发现疑似旧模板残留" : "发现疑似旧模板残留文件";
            return new OrphanFileReviewResult(status, orphanCandidates, reasons, deleteAllowed, summary);
        } catch (Exception e) {
            return new OrphanFileReviewResult("skipped", List.of(), List.of(), List.of(), "旧模板残留审查失败: " + e.getMessage());
        }
    }

    private boolean isReviewFile(Path path) {
        String name = path.getFileName().toString();
        int dotIndex = name.lastIndexOf('.');
        return dotIndex > 0 && REVIEW_EXTENSIONS.contains(name.substring(dotIndex + 1).toLowerCase(Locale.ROOT));
    }

    private String buildImportCorpus(Path projectRoot, List<Path> files) throws IOException {
        StringBuilder builder = new StringBuilder();
        for (Path file : files) {
            String relative = projectRoot.relativize(file).toString().replace("\\", "/");
            String name = file.getFileName().toString();
            if (relative.endsWith("router/index.js") || relative.endsWith("router/index.ts") || name.startsWith("main.") || name.equals("App.vue")) {
                builder.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
                continue;
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.contains("import ") || content.contains("from ")) {
                builder.append(content).append('\n');
            }
        }
        return builder.toString();
    }

    private boolean looksLikeTemplateResidual(String lowerName) {
        return lowerName.contains("landing") || lowerName.contains("navbar") || lowerName.contains("hero");
    }

    private String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex <= 0 ? fileName : fileName.substring(0, dotIndex);
    }

    public record OrphanFileReviewResult(
            String status,
            List<String> orphanCandidates,
            List<String> reasons,
            List<String> deleteAllowedFiles,
            String summary
    ) {
        public OrphanFileReviewResult {
            status = StrUtil.blankToDefault(status, "skipped");
            orphanCandidates = orphanCandidates == null ? List.of() : List.copyOf(orphanCandidates);
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            deleteAllowedFiles = deleteAllowedFiles == null ? List.of() : List.copyOf(deleteAllowedFiles);
            summary = StrUtil.blankToDefault(summary, "旧模板残留审查未执行");
        }
    }
}
