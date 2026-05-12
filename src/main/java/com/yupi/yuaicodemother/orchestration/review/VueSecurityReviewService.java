package com.yupi.yuaicodemother.orchestration.review;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class VueSecurityReviewService {

    private static final Pattern TARGET_BLANK_WITHOUT_REL = Pattern.compile("target\\s*=\\s*[\"']_blank[\"'](?![^>]*rel\\s*=)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXTERNAL_URL = Pattern.compile("https?://[^\\s\"'`)]+", Pattern.CASE_INSENSITIVE);

    public SecurityReviewResult review(String content) {
        String source = StrUtil.blankToDefault(content, "");
        String lower = source.toLowerCase();
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean usesHtmlRendering = lower.contains("v-html") || lower.contains(".innerhtml");
        boolean usesMarkdown = lower.contains("marked.parse") || lower.contains("markdown-it") || lower.contains("marked(");
        boolean hasSanitizer = lower.contains("dompurify") || lower.contains("sanitize-html") || lower.contains("sanitize(");
        if (usesHtmlRendering && usesMarkdown && !hasSanitizer) {
            blockers.add("Markdown 渲染结果通过 v-html/innerHTML 输出但未发现 HTML sanitize");
        }
        if (lower.contains(".innerhtml") && !hasSanitizer) {
            blockers.add("检测到 innerHTML 赋值且未发现 sanitize");
        }
        if (lower.contains("document.write") || lower.contains("new function") || lower.contains("createelement('script'") || lower.contains("createelement(\"script\"")) {
            blockers.add("检测到动态脚本或 document.write 高风险用法");
        }
        if (TARGET_BLANK_WITHOUT_REL.matcher(source).find()) {
            warnings.add("target=\"_blank\" 缺少 rel=\"noopener noreferrer\"");
        }
        if (EXTERNAL_URL.matcher(source).find()) {
            warnings.add("检测到外部 URL，请确认资源来源可信");
        }
        if (usesMarkdown && !hasSanitizer) {
            warnings.add("Markdown 渲染未发现 sanitize 处理");
        }
        return new SecurityReviewResult(blockers, warnings);
    }

    public record SecurityReviewResult(List<String> blockers, List<String> warnings) {
        public boolean passed() {
            return blockers == null || blockers.isEmpty();
        }
    }
}
