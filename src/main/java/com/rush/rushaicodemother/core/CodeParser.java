package com.rush.rushaicodemother.core;

import com.rush.rushaicodemother.ai.model.HtmlCodeResult;
import com.rush.rushaicodemother.ai.model.MultiFileCodeResult;
import com.rush.rushaicodemother.core.parser.HtmlCodeParser;
import com.rush.rushaicodemother.core.parser.MultiFileCodeParser;

/**
 * 代码解析器
 * 提供静态方法解析不同类型的代码内容
 *
 * @author rush
 */
@Deprecated
public class CodeParser {

    private static final HtmlCodeParser HTML_CODE_PARSER = new HtmlCodeParser();
    private static final MultiFileCodeParser MULTI_FILE_CODE_PARSER = new MultiFileCodeParser();

    /**
     * 解析 HTML 单文件代码
     */
    public static HtmlCodeResult parseHtmlCode(String codeContent) {
        return HTML_CODE_PARSER.parseCode(codeContent);
    }

    /**
     * 解析多文件代码（HTML + CSS + JS）
     */
    public static MultiFileCodeResult parseMultiFileCode(String codeContent) {
        return MULTI_FILE_CODE_PARSER.parseCode(codeContent);
    }
}
