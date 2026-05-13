package com.yupi.yuaicodemother.ai.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.yupi.yuaicodemother.core.builder.VueProjectBuilder;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import io.github.bonigarcia.wdm.WebDriverManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * 预览运行时诊断工具
 */
@Slf4j
@Component
public class PreviewRuntimeDiagnosticTool extends BaseTool {

    private static final int MAX_BROWSER_LOGS = 12;

    @Resource
    private VueProjectBuilder vueProjectBuilder;

    @Resource
    private LocalDevServerManager localDevServerManager;

    @Value("${server.port:8123}")
    private int serverPort;

    @Value("${server.servlet.context-path:/api}")
    private String contextPath;

    @Tool("用浏览器访问本地构建预览页或 dev server，采集页面标题、DOM 状态、首屏文本和 console 报错，用于排查白屏、路由异常、资源加载失败和运行时错误。")
    public String diagnosePreviewRuntime(
            @P("诊断模式：diagnoseBuildPreview、diagnoseDevServer")
            String action,
            @P("可选，显式指定要访问的目标 URL；为空时按 action 自动推导")
            String targetUrl,
            @P("可选，相对项目子目录；为空则使用整个项目")
            String relativeProjectPath,
            @P("页面加载后额外等待秒数，建议 2 到 8")
            Integer waitSeconds,
            @ToolMemoryId Long appId
    ) {
        String normalizedAction = StrUtil.blankToDefault(action, "diagnoseBuildPreview");
        try {
            String resolvedUrl = resolveTargetUrl(normalizedAction, targetUrl, relativeProjectPath, appId);
            return inspectUrl(normalizedAction, resolvedUrl, waitSeconds);
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        } catch (Exception e) {
            log.error("运行时诊断失败，action: {}", action, e);
            return "运行时诊断失败: " + e.getMessage();
        }
    }

    private String resolveTargetUrl(String action, String targetUrl, String relativeProjectPath, Long appId) {
        if (StrUtil.isNotBlank(targetUrl)) {
            return targetUrl;
        }
        if ("diagnoseDevServer".equals(action)) {
            LocalDevServerManager.DevServerSession session = localDevServerManager.getSession(appId);
            if (session == null || !session.process().isAlive()) {
                throw new IllegalArgumentException("当前没有运行中的开发服务器，请先调用【开发服务器日志工具】启动 dev server");
            }
            return session.url();
        }
        if (!"diagnoseBuildPreview".equals(action)) {
            throw new IllegalArgumentException("不支持的诊断模式 - " + action);
        }
        String projectPath = ToolPathSupport.resolvePath(relativeProjectPath, appId).toString();
        VueProjectBuilder.BuildResult buildResult = vueProjectBuilder.getRecentBuildResult(projectPath);
        if (buildResult == null) {
            throw new IllegalArgumentException("缺少可复用的最近构建结果，请先调用【本地构建诊断】或等待后台构建完成后再进行预览运行时诊断。");
        }
        if (!buildResult.success()) {
            throw new IllegalArgumentException("构建未通过，无法进行预览运行时诊断。\n" + buildResult.toDiagnosticReport());
        }
        String normalizedContextPath = normalizeContextPath(contextPath);
        return "http://127.0.0.1:" + serverPort + normalizedContextPath + "/static/vue_project_" + appId + "/dist/";
    }

    private String inspectUrl(String action, String url, Integer waitSeconds) {
        WebDriver driver = null;
        try {
            driver = createDriver();
            driver.get(url);
            waitForPageLoad(driver, waitSeconds);
            JSONObject pageInfo = collectPageInfo(driver);
            List<String> browserLogs = collectBrowserLogs(driver);
            return buildRuntimeReport(action, url, pageInfo, browserLogs);
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
    }

    private WebDriver createDriver() {
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--disable-gpu");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-extensions");
        options.addArguments("--window-size=1600,900");
        LoggingPreferences loggingPreferences = new LoggingPreferences();
        loggingPreferences.enable(LogType.BROWSER, Level.ALL);
        options.setCapability("goog:loggingPrefs", loggingPreferences);
        return new ChromeDriver(options);
    }

    private void waitForPageLoad(WebDriver driver, Integer waitSeconds) {
        int safeWaitSeconds = waitSeconds == null ? 3 : Math.max(1, Math.min(waitSeconds, 10));
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
        wait.until(webDriver -> "complete".equals(((JavascriptExecutor) webDriver)
                .executeScript("return document.readyState")));
        try {
            Thread.sleep(safeWaitSeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private JSONObject collectPageInfo(WebDriver driver) {
        String script = """
                return JSON.stringify({
                  title: document.title || '',
                  readyState: document.readyState || '',
                  currentUrl: location.href || '',
                  bodyTextLength: document.body ? document.body.innerText.trim().length : 0,
                  bodyChildCount: document.body ? document.body.children.length : 0,
                  appNodeExists: !!document.querySelector('#app, #root, [data-v-app]'),
                  appNodeChildCount: (() => {
                    const el = document.querySelector('#app, #root, [data-v-app]');
                    return el ? el.children.length : 0;
                  })(),
                  firstText: document.body ? document.body.innerText.replace(/\\s+/g, ' ').trim().slice(0, 200) : '',
                  firstH1: (() => {
                    const el = document.querySelector('h1');
                    return el ? el.innerText.trim().slice(0, 120) : '';
                  })(),
                  hash: location.hash || '',
                  scripts: Array.from(document.scripts).map(item => item.src).filter(Boolean).slice(0, 10),
                  stylesheets: Array.from(document.querySelectorAll('link[rel="stylesheet"]')).map(item => item.href).filter(Boolean).slice(0, 10)
                });
                """;
        Object result = ((JavascriptExecutor) driver).executeScript(script);
        return JSONUtil.parseObj(StrUtil.blankToDefault(String.valueOf(result), "{}"));
    }

    private List<String> collectBrowserLogs(WebDriver driver) {
        List<String> logs = new ArrayList<>();
        try {
            for (LogEntry entry : driver.manage().logs().get(LogType.BROWSER)) {
                String message = entry.getLevel() + " | " + entry.getMessage();
                logs.add(message);
                if (logs.size() >= MAX_BROWSER_LOGS) {
                    break;
                }
            }
        } catch (Exception e) {
            logs.add("浏览器日志读取失败: " + e.getMessage());
        }
        return logs;
    }

    private String buildRuntimeReport(String action, String url, JSONObject pageInfo, List<String> browserLogs) {
        List<String> findings = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        analyzePageInfo(pageInfo, browserLogs, findings, suggestions);
        StringBuilder builder = new StringBuilder();
        builder.append("运行时诊断模式: ").append(action).append('\n');
        builder.append("访问地址: ").append(url).append('\n');
        builder.append("最终地址: ").append(pageInfo.getStr("currentUrl")).append('\n');
        builder.append("页面标题: ").append(StrUtil.blankToDefault(pageInfo.getStr("title"), "(空)")).append('\n');
        builder.append("readyState: ").append(pageInfo.getStr("readyState")).append('\n');
        builder.append("hash: ").append(StrUtil.blankToDefault(pageInfo.getStr("hash"), "(空)")).append('\n');
        builder.append("正文长度: ").append(pageInfo.getInt("bodyTextLength", 0)).append('\n');
        builder.append("body 子元素数: ").append(pageInfo.getInt("bodyChildCount", 0)).append('\n');
        builder.append("挂载节点存在: ").append(pageInfo.getBool("appNodeExists", false) ? "是" : "否").append('\n');
        builder.append("挂载节点子元素数: ").append(pageInfo.getInt("appNodeChildCount", 0)).append('\n');
        builder.append("首屏文本片段: ").append(StrUtil.blankToDefault(pageInfo.getStr("firstText"), "(空)")).append('\n');
        if (findings.isEmpty()) {
            builder.append("\n诊断结论:\n- 未发现明显的浏览器级运行时异常\n");
        } else {
            builder.append("\n诊断结论:\n");
            findings.forEach(item -> builder.append("- ").append(item).append('\n'));
        }
        if (!browserLogs.isEmpty()) {
            builder.append("\nConsole 日志:\n");
            browserLogs.forEach(item -> builder.append("- ").append(item).append('\n'));
        }
        JSONArray scripts = pageInfo.getJSONArray("scripts");
        if (scripts != null && !scripts.isEmpty()) {
            builder.append("\n脚本资源:\n");
            scripts.forEach(item -> builder.append("- ").append(item).append('\n'));
        }
        if (!suggestions.isEmpty()) {
            builder.append("\n建议动作:\n");
            suggestions.stream().distinct().forEach(item -> builder.append("- ").append(item).append('\n'));
        }
        return builder.toString().trim();
    }

    private void analyzePageInfo(JSONObject pageInfo, List<String> browserLogs, List<String> findings, List<String> suggestions) {
        int bodyTextLength = pageInfo.getInt("bodyTextLength", 0);
        boolean appNodeExists = pageInfo.getBool("appNodeExists", false);
        int appNodeChildCount = pageInfo.getInt("appNodeChildCount", 0);
        String title = StrUtil.blankToDefault(pageInfo.getStr("title"), "");
        String firstText = StrUtil.blankToDefault(pageInfo.getStr("firstText"), "");
        if (!appNodeExists) {
            findings.add("页面中未发现常见挂载节点（#app / #root / [data-v-app]），可能是 index.html 入口结构有问题。");
            suggestions.add("优先读取 index.html 和 src/main.js 或 src/main.ts，检查挂载节点 id 与挂载代码是否一致。");
        } else if (bodyTextLength == 0 && appNodeChildCount == 0) {
            findings.add("挂载节点存在但没有渲染出可见内容，疑似白屏或初始化阶段崩溃。");
            suggestions.add("优先查看 Console 日志，再读取 src/main.*、App.vue、router 配置和首屏页面组件。");
        }
        if (title.toLowerCase(Locale.ROOT).contains("404") || firstText.contains("404")) {
            findings.add("页面疑似返回 404 内容，可能是预览地址、路由模式或静态资源路径不正确。");
            suggestions.add("检查 base 路径、hash 路由配置和静态预览 URL 是否指向 dist 根目录。");
        }
        for (String logLine : browserLogs) {
            String lowerLine = logLine.toLowerCase(Locale.ROOT);
            if (lowerLine.contains("severe")) {
                findings.add("浏览器出现 SEVERE 级别报错。");
            }
            if (lowerLine.contains("failed to load resource")) {
                findings.add("存在资源加载失败，常见于路径错误、base 配置错误或资源不存在。");
                suggestions.add("结合构建产物和 vite base 配置，检查脚本、样式、图片资源路径。");
            }
            if (lowerLine.contains("chunkloaderror") || lowerLine.contains("failed to fetch dynamically imported module")) {
                findings.add("存在动态模块加载失败，常见于路由懒加载路径、资源部署路径或构建产物缓存不一致。");
                suggestions.add("优先检查路由懒加载 import 路径、base 路径和 dist 资源文件名引用。");
            }
            if (lowerLine.contains("cannot read properties of undefined")
                    || lowerLine.contains("is not defined")
                    || lowerLine.contains("syntaxerror")) {
                findings.add("页面存在明确的 JavaScript 运行时异常。");
                suggestions.add("使用【项目搜索工具】定位相关变量或组件，再读取对应文件修复运行时逻辑。");
            }
        }
    }

    private String normalizeContextPath(String rawContextPath) {
        String normalized = StrUtil.blankToDefault(rawContextPath, "");
        if ("/".equals(normalized)) {
            return "";
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    @Override
    public String getToolName() {
        return "diagnosePreviewRuntime";
    }

    @Override
    public String getDisplayName() {
        return "运行时预览诊断";
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments) {
        return String.format("[工具调用] %s %s", getDisplayName(), arguments.getStr("action"));
    }

    @Override
    public String generateToolExecutedResult(JSONObject arguments, String toolResult) {
        return generateToolExecutedResult(arguments) + "\n" + summarizeResult(toolResult, 360);
    }

    private String summarizeResult(String toolResult, int maxChars) {
        if (StrUtil.isBlank(toolResult)) {
            return "";
        }
        String normalized = toolResult.replace("\r", " ").replace("\n", " ").trim();
        return StrUtil.sub(normalized, 0, Math.min(normalized.length(), maxChars));
    }
}
