package com.rush.rushaicodemother.infrastructure.security;

import java.util.Map;

/**
 * AI 生成产物的响应安全策略。
 *
 * <p>生成的 HTML/JS 与平台 API 同源（context-path `/api` 之下），会话 Cookie 虽为 HttpOnly，
 * 但同源脚本仍可发起携带凭据的 `fetch('/api/...', {credentials:'include'})`，
 * 以受害者身份调用任意业务接口。攻击者不必是外部人员 —— 模型被提示注入诱导生成一段外发脚本，
 * 就足以造成用户数据泄露。</p>
 *
 * <p>因此对预览与部署产物统一施加：</p>
 * <ul>
 *   <li>{@code sandbox}（不含 allow-same-origin）：产物获得唯一不透明源，读不到平台 Cookie 与同源接口；</li>
 *   <li>{@code connect-src 'none'}：切断 fetch / XHR / WebSocket / EventSource 外发通道；</li>
 *   <li>{@code frame-ancestors 'self'}：只允许平台自身控制台内嵌，防止第三方站点套壳；</li>
 *   <li>{@code form-action 'none'}：阻断以表单提交方式绕过 connect-src 的外发。</li>
 * </ul>
 *
 * <p>沙箱下 {@code localStorage} 不可用，因此工程模板与生成提示词统一要求业务代码走
 * {@code safeLocalStorage} 适配器（沙箱内退化为内存存储），避免模块顶层访问抛异常导致白屏。</p>
 *
 * <p>区分两条产物通路：已部署产物用 {@link #CONTENT_SECURITY_POLICY} 完全切断外发；
 * dev-server 预览用 {@link #PREVIEW_CONTENT_SECURITY_POLICY} 放通 WebSocket 以保留 Vite HMR。
 * 本类是这两套响应头的唯一来源，避免策略随时间在两条通路上漂移。</p>
 */
public final class GeneratedContentSecurityPolicy {

    /**
     * sandbox 指令：保留 allow-scripts / allow-forms / allow-popups / allow-modals，
     * 使生成的交互式应用仍可运行；<b>不授予 allow-same-origin</b>，这是隔离生效的关键 ——
     * 产物因此获得唯一不透明源，对 {@code /api} 的请求变为跨站，SameSite=lax 的会话 Cookie 不再随行。
     */
    private static final String SANDBOX =
            "sandbox allow-scripts allow-forms allow-popups allow-modals";

    private static final String COMMON_DIRECTIVES = String.join("; ",
            "default-src 'self'",
            "form-action 'none'",
            "frame-ancestors 'self'",
            "base-uri 'none'"
    );

    /**
     * 已部署产物的策略：完全切断外发通道。
     *
     * <p>部署产物是构建后的静态文件，不需要 HMR，因此可以用最严格的 {@code connect-src 'none'}。</p>
     */
    public static final String CONTENT_SECURITY_POLICY = String.join("; ",
            SANDBOX, COMMON_DIRECTIVES, "connect-src 'none'");

    /**
     * dev-server 预览的策略：放通 WebSocket 以保留 Vite HMR。
     *
     * <p>不透明源下 {@code 'self'} 不匹配任何来源，若沿用 {@code connect-src 'none'}，
     * Vite 的 {@code vite-hmr} WebSocket 会被拦截，预览失去热更新 —— 这恰好损害我们要改善的预览体验。
     * 因此按 scheme 放通 ws/wss。</p>
     *
     * <p>安全权衡：真正要防的是「生成脚本以受害者身份调用平台接口」，而该攻击已由 sandbox 的不透明源
     * 阻断（Cookie 不再随行），{@code connect-src} 在此只是纵深防御。被沙箱隔离的页面本身不持有平台凭据，
     * 放通 WebSocket 的额外暴露面很小，换回热更新是值得的。HTTP 外发（fetch/XHR）仍被阻断。</p>
     */
    public static final String PREVIEW_CONTENT_SECURITY_POLICY = String.join("; ",
            SANDBOX, COMMON_DIRECTIVES, "connect-src ws: wss:");

    /** 与 CSP 同时下发的补充响应头，覆盖不支持 CSP frame-ancestors 的旧浏览器。 */
    public static final Map<String, String> ADDITIONAL_HEADERS = Map.of(
            "X-Content-Type-Options", "nosniff",
            "X-Frame-Options", "SAMEORIGIN",
            "Referrer-Policy", "no-referrer",
            "Cross-Origin-Resource-Policy", "same-origin"
    );

    /** 响应头名称常量，避免调用方各自硬编码字符串。 */
    public static final String CONTENT_SECURITY_POLICY_HEADER = "Content-Security-Policy";

    private GeneratedContentSecurityPolicy() {
    }
}
