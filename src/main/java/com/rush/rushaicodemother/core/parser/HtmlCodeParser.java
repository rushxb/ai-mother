package com.rush.rushaicodemother.core.parser;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.model.HtmlCodeResult;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML 单文件代码解析器
 *
 * @author rush
 */
public class HtmlCodeParser implements CodeParser<HtmlCodeResult> {

    private static final Pattern HTML_CODE_PATTERN = Pattern.compile("```html\\s*\\n([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPLETE_HTML_PATTERN = Pattern.compile("(?is)(<!DOCTYPE\\s+html[^>]*>\\s*)?<html[\\s\\S]*?</html>");

    @Override
    public HtmlCodeResult parseCode(String codeContent) {
        HtmlCodeResult result = new HtmlCodeResult();
        String htmlCode = extractHtmlCode(codeContent);
        if (StrUtil.isBlank(htmlCode)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI 返回内容不是有效的 HTML 页面代码");
        }
        result.setHtmlCode(htmlCode.trim());
        return result;
    }

    /**
     * 提取 HTML 代码内容
     *
     * @param content 原始内容
     * @return HTML代码
     */
    private String extractHtmlCode(String content) {
        Matcher matcher = HTML_CODE_PATTERN.matcher(content);
        if (matcher.find()) {
            String htmlCode = matcher.group(1);
            if (isValidHtmlDocument(htmlCode)) {
                return htmlCode;
            }
            return null;
        }
        String trimmedContent = StrUtil.trim(content);
        if (isValidHtmlDocument(trimmedContent)) {
            return trimmedContent;
        }
        Matcher fullHtmlMatcher = COMPLETE_HTML_PATTERN.matcher(content);
        if (fullHtmlMatcher.find()) {
            String htmlCode = fullHtmlMatcher.group();
            if (isValidHtmlDocument(htmlCode)) {
                return htmlCode;
            }
        }
        return null;
    }

    private boolean isValidHtmlDocument(String content) {
        if (StrUtil.isBlank(content)) {
            return false;
        }
        String normalized = content.trim().toLowerCase();
        boolean hasHtml = normalized.contains("<html");
        boolean hasHead = normalized.contains("<head");
        boolean hasBody = normalized.contains("<body");
        boolean hasClosingHtml = normalized.contains("</html>");
        boolean hasClosingBody = normalized.contains("</body>");
        return hasHtml && hasHead && hasBody && hasClosingHtml && hasClosingBody;
    }
}
