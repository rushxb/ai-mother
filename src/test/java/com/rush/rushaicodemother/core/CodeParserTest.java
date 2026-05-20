package com.rush.rushaicodemother.core;

import com.rush.rushaicodemother.ai.model.HtmlCodeResult;
import com.rush.rushaicodemother.ai.model.MultiFileCodeResult;
import com.rush.rushaicodemother.core.parser.HtmlCodeParser;
import com.rush.rushaicodemother.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CodeParserTest {

    private final HtmlCodeParser htmlCodeParser = new HtmlCodeParser();

    @Test
    void parseHtmlCode() {
        String codeContent = """
                随便写一段描述：
                ```html
                <!DOCTYPE html>
                <html>
                <head>
                    <title>测试页面</title>
                </head>
                <body>
                    <h1>Hello World!</h1>
                </body>
                </html>
                ```
                随便写一段描述
                """;
        HtmlCodeResult result = CodeParser.parseHtmlCode(codeContent);
        assertNotNull(result);
        assertNotNull(result.getHtmlCode());
    }

    @Test
    void parseHtmlCodeWithChineseContent() {
        String codeContent = """
                ```html
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                    <meta charset="UTF-8">
                    <title>系统配置展示页</title>
                </head>
                <body>
                    <main>
                        <h1>系统配置</h1>
                        <p>这里展示中文内容，不应该被误判。</p>
                    </main>
                </body>
                </html>
                ```
                """;
        HtmlCodeResult result = htmlCodeParser.parseCode(codeContent);
        assertNotNull(result);
        assertTrue(result.getHtmlCode().contains("系统配置"));
    }

    @Test
    void parsePlainTextAnswerShouldFailForHtml() {
        String plainText = """
                我无法查看或获取您的系统配置信息。
                我是一个 AI 语言模型，运行在安全的服务器环境中，无法访问您的本地系统。
                """;
        assertThrows(BusinessException.class, () -> htmlCodeParser.parseCode(plainText));
    }

    @Test
    void parseMultiFileCode() {
        String codeContent = """
                创建一个完整的网页：
                ```html
                <!DOCTYPE html>
                <html>
                <head>
                    <title>多文件示例</title>
                    <link rel="stylesheet" href="style.css">
                </head>
                <body>
                    <h1>欢迎使用</h1>
                    <script src="script.js"></script>
                </body>
                </html>
                ```
                ```css
                h1 {
                    color: blue;
                    text-align: center;
                }
                ```
                ```js
                console.log('页面加载完成');
                ```
                文件创建完成！
                """;
        MultiFileCodeResult result = CodeParser.parseMultiFileCode(codeContent);
        assertNotNull(result);
        assertNotNull(result.getHtmlCode());
        assertNotNull(result.getCssCode());
        assertNotNull(result.getJsCode());
    }

    @Test
    void parsePlainTextAnswerShouldFailForMultiFile() {
        String plainText = """
                很抱歉，我无法还原到之前的博客内容。
                不过我可以重新帮您生成一个新的博客页面。
                """;
        assertThrows(BusinessException.class, () -> CodeParser.parseMultiFileCode(plainText));
    }
}
