package com.yupi.yuaicodemother.orchestration.skill;

import cn.hutool.core.util.StrUtil;
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
    }

    public List<GenerationSkill> match(String userMessage) {
        String normalized = normalize(userMessage);
        if (StrUtil.isBlank(normalized)) {
            return List.of();
        }
        return skills.stream()
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
    }

    public List<String> modules(List<GenerationSkill> matchedSkills) {
        if (matchedSkills == null || matchedSkills.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> modules = new LinkedHashSet<>();
        matchedSkills.forEach(skill -> modules.addAll(skill.modules()));
        return List.copyOf(modules);
    }

    public List<String> contextFileHints(List<GenerationSkill> matchedSkills) {
        if (matchedSkills == null || matchedSkills.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        matchedSkills.forEach(skill -> hints.addAll(skill.contextFileHints()));
        return List.copyOf(hints);
    }

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
            return result;
        } catch (IOException e) {
            return List.of();
        }
    }

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
            return List.of();
        }
    }

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

    private record SkillMatch(GenerationSkill skill, int score) {
    }
}
