package com.rush.rushaicodemother.orchestration.context.repository;

import cn.hutool.crypto.digest.DigestUtil;
import com.rush.rushaicodemother.orchestration.context.AiContextBoundaryService;
import com.rush.rushaicodemother.orchestration.context.repository.ProtectedRepositoryContextEnvelope.PromptInjectionRisk;
import com.rush.rushaicodemother.orchestration.context.repository.ProtectedRepositoryContextEnvelope.Sensitivity;
import com.rush.rushaicodemother.orchestration.context.repository.ProtectedRepositoryContextEnvelope.SourceEvidence;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 项目上下文信任深模块：把检索事实转换为唯一允许进入模型的受保护信封。
 *
 * <p>调用者负责通过受控文件系统完成检索；本服务统一负责来源指纹、秘密编辑、
 * 提示注入风险标记、Token 预算和出站授权，避免各条 Pipeline 自行拼接安全规则。</p>
 */
@Service
public class RepositoryContextTrustService {

    private static final int APPROXIMATE_CHARS_PER_TOKEN = 4;
    private static final int BOUNDARY_OVERHEAD_CHARS = 1_024;
    private static final String TRUNCATION_MARKER = "\n[项目上下文已按模型 Token 预算截断]";
    private static final List<Pattern> HIGH_RISK_PATTERNS = List.of(
            Pattern.compile("(?i)ignore\\s+(?:all\\s+)?(?:previous|prior|system)\\s+instructions"),
            Pattern.compile("(?i)(?:忽略|无视).{0,20}(?:系统|之前|以上).{0,20}(?:指令|提示)"),
            Pattern.compile("(?i)reveal.{0,20}(?:secret|credential|token|system prompt)"),
            Pattern.compile("(?i)(?:泄露|输出|展示).{0,20}(?:密钥|令牌|系统提示词)"),
            Pattern.compile("(?i)BEGIN_(?:SYSTEM|DEVELOPER)_MESSAGE")
    );
    private static final List<Pattern> SUSPICIOUS_PATTERNS = List.of(
            Pattern.compile("(?i)you are (?:chatgpt|an ai|the system)"),
            Pattern.compile("(?i)(?:system|developer)\\s*(?:message|prompt)\\s*[:：]"),
            Pattern.compile("(?i)(?:执行|run).{0,20}(?:命令|command|shell)")
    );

    private final AiContextBoundaryService contextBoundaryService;

    public RepositoryContextTrustService(AiContextBoundaryService contextBoundaryService) {
        this.contextBoundaryService = Objects.requireNonNull(
                contextBoundaryService, "AI 上下文边界服务不能为空");
    }

    /** 将检索结果转换为可进入模型的受保护信封。 */
    public ProtectedRepositoryContextEnvelope protect(
            RepositoryContextRequest request,
            RetrievedRepositoryEvidence evidence) {
        Objects.requireNonNull(request, "项目上下文请求不能为空");
        RetrievedRepositoryEvidence safeEvidence = evidence == null
                ? new RetrievedRepositoryEvidence("", List.of()) : evidence;

        int maxProtectedChars = Math.multiplyExact(
                request.tokenBudget(), APPROXIMATE_CHARS_PER_TOKEN);
        int maxSourceChars = Math.max(1, maxProtectedChars - BOUNDARY_OVERHEAD_CHARS);
        BoundedText boundedContext = bound(safeEvidence.structuralContext(), maxSourceChars);
        AiContextBoundaryService.ProtectedContext protectedContext =
                contextBoundaryService.protectRepositoryContext(boundedContext.content());

        List<SourceEvidence> sources = new ArrayList<>();
        PromptInjectionRisk overallRisk = detectRisk(safeEvidence.structuralContext());
        boolean sourceRedacted = false;
        boolean sourceTruncated = boundedContext.truncated() || protectedContext.truncated();
        for (RetrievedRepositoryEvidence.FileEvidence file : safeEvidence.files()) {
            AiContextBoundaryService.ProtectedContext protectedFile =
                    contextBoundaryService.protectRepositoryContext(file.content());
            PromptInjectionRisk risk = detectRisk(file.content());
            overallRisk = maxRisk(overallRisk, risk);
            sourceRedacted = sourceRedacted || protectedFile.redacted();
            sourceTruncated = sourceTruncated || file.truncated() || protectedFile.truncated();
            sources.add(new SourceEvidence(
                    file.relativePath(),
                    protectedFile.digest(),
                    protectedFile.sourceChars(),
                    file.truncated() || protectedFile.truncated(),
                    protectedFile.redacted() ? Sensitivity.SENSITIVE_REDACTED : Sensitivity.NORMAL,
                    risk,
                    request.outboundAllowed()
            ));
        }
        sources.sort(Comparator.comparing(SourceEvidence::relativePath));
        String workspaceVersion = workspaceVersion(
                request, protectedContext.digest(), sources);
        int estimatedTokens = estimateTokens(protectedContext.content());
        if (estimatedTokens > request.tokenBudget()) {
            throw new IllegalStateException("受保护项目上下文超出冻结 Token 预算");
        }
        return new ProtectedRepositoryContextEnvelope(
                protectedContext.content(),
                workspaceVersion,
                sources,
                request.tokenBudget(),
                estimatedTokens,
                protectedContext.redacted() || sourceRedacted,
                sourceTruncated,
                overallRisk,
                request.outboundAllowed()
        );
    }

    private String workspaceVersion(RepositoryContextRequest request,
                                    String contextDigest,
                                    List<SourceEvidence> sources) {
        StringBuilder identity = new StringBuilder(request.purpose().name())
                .append('|').append(contextDigest);
        for (SourceEvidence source : sources) {
            identity.append('\n')
                    .append(source.relativePath())
                    .append('|')
                    .append(source.contentFingerprint());
        }
        return DigestUtil.sha256Hex(identity.toString());
    }

    private PromptInjectionRisk detectRisk(String value) {
        String content = value == null ? "" : value;
        if (matchesAny(content, HIGH_RISK_PATTERNS)) {
            return PromptInjectionRisk.HIGH;
        }
        if (matchesAny(content, SUSPICIOUS_PATTERNS)) {
            return PromptInjectionRisk.SUSPICIOUS;
        }
        return PromptInjectionRisk.NONE;
    }

    private boolean matchesAny(String content, List<Pattern> patterns) {
        return patterns.stream().anyMatch(pattern -> pattern.matcher(content).find());
    }

    private PromptInjectionRisk maxRisk(PromptInjectionRisk left, PromptInjectionRisk right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    private int estimateTokens(String content) {
        int chars = content == null ? 0 : content.length();
        return (chars + APPROXIMATE_CHARS_PER_TOKEN - 1) / APPROXIMATE_CHARS_PER_TOKEN;
    }

    private BoundedText bound(String value, int maxChars) {
        String content = value == null ? "" : value;
        if (content.length() <= maxChars) {
            return new BoundedText(content, false);
        }
        if (maxChars <= TRUNCATION_MARKER.length()) {
            return new BoundedText(content.substring(0, maxChars), true);
        }
        int available = maxChars - TRUNCATION_MARKER.length();
        int head = Math.max(1, available * 2 / 3);
        int tail = available - head;
        return new BoundedText(
                content.substring(0, head)
                        + TRUNCATION_MARKER
                        + content.substring(content.length() - tail),
                true
        );
    }

    private record BoundedText(String content, boolean truncated) {
    }
}
