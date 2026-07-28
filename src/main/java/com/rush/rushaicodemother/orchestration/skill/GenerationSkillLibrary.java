package com.rush.rushaicodemother.orchestration.skill;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 生成技能库：从 markdown 技能文件中发现并匹配技能。
 */
@Slf4j
@Component
public class GenerationSkillLibrary {

    private static final int MAX_MATCHED_SKILLS = 3;
    private static final String CLASS_PATH_PATTERN = "classpath*:agent-skills/**/SKILL.md";

    private final List<GenerationSkill> skills;

    public GenerationSkillLibrary() {
        this(List.of(Path.of(".agents", "skills")), true);
    }

    public GenerationSkillLibrary(Path filesystemSkillRoot) {
        this(List.of(filesystemSkillRoot), false);
    }

    public GenerationSkillLibrary(List<Path> filesystemSkillRoots, boolean includeClasspathSkills) {
        this.skills = List.copyOf(loadSkills(filesystemSkillRoots, includeClasspathSkills));
        logSkillRegistrySummary(filesystemSkillRoots, includeClasspathSkills, this.skills);
    }

    /**
 * 返回{@code match}。
 *
 * @param userMessage 用户消息
 * @return 生成{@code Skill}{@code Library}集合
 */
    public List<GenerationSkill> match(String userMessage) {
        String normalized = normalize(userMessage);
        if (StrUtil.isBlank(normalized)) {
            return List.of();
        }
        List<GenerationSkill> matchedSkills = skills.stream()
                .map(skill -> new SkillMatch(skill, score(skill, normalized)))
                .filter(match -> match.score() > 0)
                .sorted((left, right) -> {
                    int scoreCompare = Integer.compare(right.score(), left.score());
                    if (scoreCompare != 0) {
                        return scoreCompare;
                    }
                    return left.skill().title().compareToIgnoreCase(right.skill().title());
                })
                .limit(MAX_MATCHED_SKILLS)
                .map(SkillMatch::skill)
                .toList();
        if (!matchedSkills.isEmpty()) {
            log.info("技能匹配命中 {} 个: {}", matchedSkills.size(),
                    matchedSkills.stream().map(GenerationSkill::id).toList());
            matchedSkills.forEach(skill ->
                    log.info("技能匹配详情: id={}, title={}, sourcePath={}", skill.id(), skill.title(), skill.sourcePath()));
        } else {
            log.info("技能匹配结果为空，输入未命中任何已注册技能");
        }
        return matchedSkills;
    }

    /**
 * 返回{@code modules}。
 *
 * @param matchedSkills 待处理的 {@code matchedSkills} 集合
 * @return 生成{@code Skill}{@code Library}集合
 */
    public List<String> modules(List<GenerationSkill> matchedSkills) {
        if (matchedSkills == null || matchedSkills.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> modules = new LinkedHashSet<>();
        matchedSkills.forEach(skill -> modules.addAll(skill.modules()));
        return List.copyOf(modules);
    }

    /**
 * 返回上下文文件{@code Hints}。
 *
 * @param matchedSkills 待处理的 {@code matchedSkills} 集合
 * @return 生成{@code Skill}{@code Library}集合
 */
    public List<String> contextFileHints(List<GenerationSkill> matchedSkills) {
        if (matchedSkills == null || matchedSkills.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        matchedSkills.forEach(skill -> hints.addAll(skill.contextFileHints()));
        return List.copyOf(hints);
    }

    /**
 * 将当前对象转换为{@code Payloads}。
 *
 * @param matchedSkills 待处理的 {@code matchedSkills} 集合
 * @return {@code Payloads}集合
 */
    public List<Map<String, Object>> toPayloads(List<GenerationSkill> matchedSkills) {
        if (matchedSkills == null || matchedSkills.isEmpty()) {
            return List.of();
        }
        return matchedSkills.stream()
                .map(skill -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("id", skill.id());
                    payload.put("title", skill.title());
                    payload.put("description", skill.description());
                    payload.put("keywords", skill.keywords());
                    payload.put("modules", skill.modules());
                    payload.put("contextFileHints", skill.contextFileHints());
                    payload.put("implementationHints", skill.implementationHints());
                    payload.put("validationHints", skill.validationHints());
                    payload.put("databaseRequired", skill.databaseRequired());
                    payload.put("promptInstructions", skill.promptInstructions());
                    payload.put("sourcePath", skill.sourcePath());
                    return payload;
                })
                .toList();
    }

    /** 加载{@code Skills}。 */
    private List<GenerationSkill> loadSkills(List<Path> filesystemSkillRoots, boolean includeClasspathSkills) {
        Map<String, GenerationSkill> loaded = new LinkedHashMap<>();
        if (includeClasspathSkills) {
            loadClasspathSkills().forEach(skill -> loaded.put(skill.id(), skill));
        }
        if (filesystemSkillRoots != null) {
            for (Path root : filesystemSkillRoots) {
                loadFileSystemSkills(root).forEach(skill -> loaded.put(skill.id(), skill));
            }
        }
        return new ArrayList<>(loaded.values());
    }

    /** 加载{@code Classpath}{@code Skills}。 */
    private List<GenerationSkill> loadClasspathSkills() {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources(CLASS_PATH_PATTERN);
            List<GenerationSkill> result = new ArrayList<>();
            for (Resource resource : resources) {
                if (!resource.isReadable()) {
                    continue;
                }
                try {
                    String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    result.add(parseSkill(content, resource.getDescription()));
                } catch (Exception ignored) {
                }
            }
            log.info("已扫描 classpath 技能目录，pattern={}, 命中数量={}", CLASS_PATH_PATTERN, result.size());
            return result;
        } catch (IOException e) {
            log.warn("扫描 classpath 技能目录失败，pattern={}", CLASS_PATH_PATTERN, LogExceptionSanitizer.sanitize(e));
            return List.of();
        }
    }

    /** 加载文件{@code System}{@code Skills}。 */
    private List<GenerationSkill> loadFileSystemSkills(Path root) {
        if (root == null || !Files.exists(root) || !Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(path -> Files.isRegularFile(path) && "SKILL.md".equalsIgnoreCase(path.getFileName().toString()))
                    .map(path -> {
                        try {
                            return parseSkill(Files.readString(path, StandardCharsets.UTF_8), root.relativize(path).toString());
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .toList();
        } catch (IOException e) {
            log.warn("扫描文件系统技能目录失败，root={}", root, LogExceptionSanitizer.sanitize(e));
            return List.of();
        }
    }

    /** 解析{@code Skill}。 */
    private GenerationSkill parseSkill(String content, String sourcePath) {
        String normalizedContent = StrUtil.blankToDefault(content, "").replace("\r\n", "\n");
        String[] lines = normalizedContent.split("\n", -1);
        int frontMatterEnd = -1;
        if (lines.length > 0 && "---".equals(lines[0].trim())) {
            for (int i = 1; i < lines.length; i++) {
                if ("---".equals(lines[i].trim())) {
                    frontMatterEnd = i;
                    break;
                }
            }
        }

        Map<String, String> meta = new LinkedHashMap<>();
        String body = normalizedContent.trim();
        if (frontMatterEnd > 0) {
            for (int i = 1; i < frontMatterEnd; i++) {
                String line = lines[i];
                int colonIndex = line.indexOf(':');
                if (colonIndex <= 0) {
                    continue;
                }
                String key = line.substring(0, colonIndex).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colonIndex + 1).trim();
                meta.put(key, value);
            }
            body = String.join("\n", List.of(lines).subList(frontMatterEnd + 1, lines.length)).trim();
        }

        String id = firstNonBlank(meta.get("id"), meta.get("name"), deriveFallbackId(sourcePath));
        String title = firstNonBlank(meta.get("title"), meta.get("name"), id);
        String description = firstNonBlank(meta.get("description"), "");
        List<String> keywords = splitList(meta.get("keywords"), meta.get("tags"), id, title);
        List<String> modules = splitList(meta.get("modules"));
        List<String> contextFileHints = splitList(meta.get("contextfilehints"), meta.get("filehints"));
        List<String> implementationHints = splitList(meta.get("implementationhints"), meta.get("instructions"));
        List<String> validationHints = splitList(meta.get("validationhints"));
        boolean databaseRequired = Boolean.parseBoolean(firstNonBlank(meta.get("databaserequired"), "false"));
        return new GenerationSkill(
                normalizeId(id),
                title,
                description,
                keywords,
                modules,
                contextFileHints,
                implementationHints,
                validationHints,
                databaseRequired,
                body,
                sourcePath
        );
    }

    /** 返回{@code score}。 */
    private int score(GenerationSkill skill, String normalizedText) {
        int score = 0;
        if (contains(normalizedText, skill.id())) {
            score += 5;
        }
        if (contains(normalizedText, skill.title())) {
            score += 4;
        }
        for (String keyword : skill.keywords()) {
            if (contains(normalizedText, keyword)) {
                score += 3;
            }
        }
        for (String module : skill.modules()) {
            if (contains(normalizedText, module)) {
                score += 2;
            }
        }
        return score;
    }

    private boolean contains(String normalizedText, String keyword) {
        if (StrUtil.isBlank(normalizedText) || StrUtil.isBlank(keyword)) {
            return false;
        }
        return normalizedText.contains(normalize(keyword));
    }

    /** 返回{@code split}列表。 */
    private List<String> splitList(String... values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (StrUtil.isBlank(value)) {
                continue;
            }
            String normalized = value
                    .replace('，', ',')
                    .replace('；', ',')
                    .replace('、', ',')
                    .replace('|', ',')
                    .replace(';', ',');
            for (String item : normalized.split(",")) {
                String trimmed = StrUtil.trim(item);
                if (StrUtil.isNotBlank(trimmed)) {
                    result.add(trimmed);
                }
            }
        }
        return List.copyOf(result);
    }

    /** 返回首次{@code Non}{@code Blank}。 */
    private String firstNonBlank(String... values) {
        if (values == null || values.length == 0) {
            return "";
        }
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    /** 返回{@code derive}回退编号。 */
    private String deriveFallbackId(String sourcePath) {
        if (StrUtil.isBlank(sourcePath)) {
            return "skill";
        }
        String cleaned = sourcePath.replace('\\', '/');
        int lastSlash = cleaned.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? cleaned.substring(lastSlash + 1) : cleaned;
        if ("SKILL.md".equalsIgnoreCase(fileName) && lastSlash > 0) {
            int parentSlash = cleaned.lastIndexOf('/', lastSlash - 1);
            if (parentSlash >= 0) {
                return cleaned.substring(parentSlash + 1, lastSlash);
            }
            return cleaned.substring(0, lastSlash);
        }
        if (fileName.endsWith(".md")) {
            return fileName.substring(0, fileName.length() - 3);
        }
        return fileName;
    }

    private String normalizeId(String value) {
        return normalize(value).replace(' ', '-');
    }

    private String normalize(String value) {
        return StrUtil.blankToDefault(value, "").trim().toLowerCase(Locale.ROOT);
    }

    /** 处理日志{@code Skill}注册器汇总。 */
    private void logSkillRegistrySummary(List<Path> filesystemSkillRoots,
                                         boolean includeClasspathSkills,
                                         List<GenerationSkill> loadedSkills) {
        if (loadedSkills.isEmpty()) {
            log.warn("技能注册结果为空。includeClasspathSkills={}, filesystemSkillRoots={}", includeClasspathSkills, filesystemSkillRoots);
            return;
        }
        log.info("技能注册完成，共 {} 个技能。includeClasspathSkills={}, filesystemSkillRoots={}",
                loadedSkills.size(), includeClasspathSkills, filesystemSkillRoots);
        for (GenerationSkill skill : loadedSkills) {
            log.info("已注册技能: id={}, title={}, sourcePath={}", skill.id(), skill.title(), skill.sourcePath());
        }
    }

    private record SkillMatch(GenerationSkill skill, int score) {
    }
}
