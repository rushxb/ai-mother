package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.config.EditLocatorProperties;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从可视化编辑器 DOM 选择器和文本信号中查找源文件。 */
@Service
@RequiredArgsConstructor
public class SelectedElementFileLocator {

    private static final Pattern CLASS_PATTERN = Pattern.compile("\\.([A-Za-z_][\\w-]*)");
    private static final Pattern CONTENT_PATTERN = Pattern.compile("- \\u5f53\\u524d\\u5185\\u5bb9:\\s*(.+)");
    private static final Pattern CHINESE_TEXT_PATTERN = Pattern.compile("[\\p{IsHan}]{2,}");
    private static final String SELECTED_ELEMENT_HEADING = "\u9009\u4e2d\u5143\u7d20\u4fe1\u606f";
    private static final String CART_TEXT = "\u52a0\u5165\u8d2d\u7269\u8f66";

    private final EditWorkspaceFileService workspaceFileService;
    private final EditLocatorProperties properties;

    /**
 * 返回{@code locate}。
 *
 * @param workspace 工作区
 * @param userMessage 用户消息
 * @return {@code Selected}{@code Element}文件{@code Locator}集合
 */
    public List<EditFileCandidate> locate(GenerationWorkspace workspace, String userMessage) {
        if (workspace == null || StrUtil.isBlank(userMessage) || !userMessage.contains(SELECTED_ELEMENT_HEADING)) {
            return List.of();
        }
        LinkedHashSet<String> classNames = extractClassNames(userMessage);
        LinkedHashSet<String> textSignals = extractTextSignals(userMessage);
        if (classNames.isEmpty() && textSignals.isEmpty()) {
            return List.of();
        }

        return workspaceFileService.scanIndexableFiles(workspace, "").stream()
                .map(file -> scoreFile(workspace, file, classNames, textSignals))
                .filter(candidate -> candidate != null && candidate.score() > 0)
                .sorted(Comparator.comparingInt(EditFileCandidate::score).reversed()
                        .thenComparing(EditFileCandidate::relativePath))
                .limit(properties.getMaxCandidateFiles())
                .toList();
    }

    /** 从输入中提取{@code Class}{@code Names}。 */
    private LinkedHashSet<String> extractClassNames(String userMessage) {
        LinkedHashSet<String> classNames = new LinkedHashSet<>();
        String selector = extractSelector(userMessage);
        if (StrUtil.isBlank(selector)) {
            return classNames;
        }
        Matcher matcher = CLASS_PATTERN.matcher(selector);
        while (matcher.find() && classNames.size() < 20) {
            String className = matcher.group(1);
            if (StrUtil.isNotBlank(className) && !className.startsWith("nth-child")) {
                classNames.add(className);
            }
        }
        return classNames;
    }

    /** 从输入中提取{@code Selector}。 */
    private String extractSelector(String userMessage) {
        String prefix = "- \u9009\u62e9\u5668:";
        for (String line : userMessage.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    /** 从输入中提取{@code Text}{@code Signals}。 */
    private LinkedHashSet<String> extractTextSignals(String userMessage) {
        LinkedHashSet<String> signals = new LinkedHashSet<>();
        Matcher matcher = CONTENT_PATTERN.matcher(userMessage);
        if (!matcher.find()) {
            return signals;
        }
        String content = matcher.group(1).trim();
        if (StrUtil.isBlank(content)) {
            return signals;
        }
        if (content.contains(CART_TEXT)) {
            signals.add(CART_TEXT);
        }
        Matcher chineseMatcher = CHINESE_TEXT_PATTERN.matcher(content);
        while (chineseMatcher.find() && signals.size() < 8) {
            signals.add(chineseMatcher.group());
        }
        return signals;
    }

    /** 返回{@code score}文件。 */
    private EditFileCandidate scoreFile(GenerationWorkspace workspace,
                                        EditWorkspaceFile file,
                                        Set<String> classNames,
                                        Set<String> textSignals) {
        String content = workspaceFileService.readUtf8(workspace, file).orElse(null);
        if (content == null) {
            return null;
        }

        int score = 0;
        List<String> matchedTerms = new ArrayList<>();
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (String className : classNames) {
            if (file.fileName().equalsIgnoreCase(kebabToPascal(className) + ".vue")) {
                score += 180;
                matchedTerms.add(className);
            }
            if (content.contains(className)) {
                score += className.contains("-") ? 70 : 35;
                matchedTerms.add(className);
            }
        }
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (String signal : textSignals) {
            if (content.contains(signal)) {
                score += CART_TEXT.equals(signal) ? 150 : 60;
                matchedTerms.add(signal);
            }
        }
        if (file.relativePath().endsWith(".css") && classNames.stream().anyMatch(content::contains)) {
            score += 40;
        }
        if (score <= 0) {
            return null;
        }
        return new EditFileCandidate(
                file.relativePath(),
                file.fileName(),
                "selected_element",
                score,
                "\u9009\u4e2d\u5143\u7d20\u7684 DOM \u7c7b\u540d\u6216\u6587\u672c\u547d\u4e2d\u6e90\u7801",
                matchedTerms.stream().distinct().toList()
        );
    }

    /** 返回{@code kebab}{@code To}{@code Pascal}。 */
    private String kebabToPascal(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String part : value.split("-")) {
            if (StrUtil.isNotBlank(part)) {
                builder.append(part.substring(0, 1).toUpperCase()).append(part.substring(1));
            }
        }
        return builder.toString();
    }
}
