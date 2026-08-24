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
 *   <li>部署产物用 {@code connect-src 'none'} 完全切断外发，dev-server 预览仅保留同源 HMR；</li>
 *   <li>{@code frame-ancestors 'self'}：只允许平台自身控制台内嵌，防止第三方站点套壳；</li>
 *   <li>{@code form-action 'none'}：阻断以表单提交方式绕过 connect-src 的外发。</li>
 * </ul>
 *
 * <p>沙箱下 {@code localStorage} 不可用，因此工程模板与生成提示词统一要求业务代码走
 * {@code safeLocalStorage} 适配器（沙箱内退化为内存存储），避免模块顶层访问抛异常导致白屏。</p>
 *
 * <p>区分两条产物通路：已部署产物用 {@link #CONTENT_SECURITY_POLICY} 完全切断外发；
 * dev-server 预览用 {@link #PREVIEW_CONTENT_SECURITY_POLICY} 只放通公开响应同源连接，
 * 以保留经平台代理的 Vite HMR。
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
     * dev-server 预览策略：只允许连接公开响应自身的来源。
     *
     * <p>CSP 的 {@code 'self'} 按策略响应 URL 的来源匹配，不使用 sandbox 后页面的 opaque origin。
     * 因此 Vite 仍可连接平台同源的 HMR WebSocket，同时生成脚本无法再通过任意
     * {@code ws:}/{@code wss:} 主机外发数据。反向代理必须把 HMR 保持在公开同源路径；
     * 直连内部 Dev Server 端口会被浏览器明确拦截。</p>
     */
    public static final String PREVIEW_CONTENT_SECURITY_POLICY = String.join("; ",
            SANDBOX, COMMON_DIRECTIVES, "connect-src 'self'");

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
