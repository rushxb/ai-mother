package com.rush.rushaicodemother.orchestration.review;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 后端与全栈生成质量门禁。
 * <p>
 * 该检查在代码生成前执行，确保提示词和 artifact 已包含足够的工程约束。
 */
@Service
public class BackendQualityReviewService {

    public BackendReviewResult review(GenerationAgentContext context, String prompt) {
        if (context == null || !requiresBackendReview(context.getTargetType())) {
            return BackendReviewResult.passed(List.of(), List.of("非后端目标，跳过后端门禁"));
        }
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> passes = new ArrayList<>();
        String normalizedPrompt = StrUtil.blankToDefault(prompt, "").toLowerCase(Locale.ROOT);

        if (!normalizedPrompt.contains("sqlite") || !normalizedPrompt.contains("repository")) {
            blockers.add("后端生成规范缺少 SQLite Repository 约束");
        } else {
            passes.add("已声明 SQLite Repository 生成约束");
        }
        if (!normalizedPrompt.contains("参数化 sql") && !normalizedPrompt.contains("参数化sql")
                && !normalizedPrompt.contains("parameterized")) {
            blockers.add("后端生成规范缺少参数化 SQL 约束");
        } else {
            passes.add("已声明参数化 SQL 约束");
        }
        if (!normalizedPrompt.contains("internal/modules") || !normalizedPrompt.contains("internal/domain")) {
            blockers.add("后端生成规范未锁定 internal/domain + internal/modules 分层");
        } else {
            passes.add("已锁定动态模块目录结构");
        }
        if (containsDangerousSql(normalizedPrompt)) {
            blockers.add("生成规范包含危险 SQL 操作");
        }

        GenerationArtifact apiContract = context.getArtifact("api_contract").orElse(null);
        if (apiContract == null || apiContract.payload() == null || apiContract.payload().isEmpty()) {
            blockers.add("缺少 API 字段契约 artifact");
        } else {
            passes.add("API 字段契约 artifact 已生成");
            Object contractObj = apiContract.payload().get("contract");
            if (contractObj instanceof Map<?, ?> contractMap) {
                Object entities = contractMap.get("entities");
                if (entities instanceof List<?> list && list.isEmpty()) {
                    warnings.add("API 字段契约实体为空，将由代码生成器从用户需求补齐；复杂需求建议走重型生成");
                }
            }
        }

        if (context.getTargetType() == CodeGenTypeEnum.FULL_STACK_PROJECT) {
            if (!normalizedPrompt.contains("vite_api_base_url")) {
                blockers.add("全栈生成规范缺少 VITE_API_BASE_URL 前端调用约束");
            }
            if (!normalizedPrompt.contains("server_addr")) {
                blockers.add("全栈生成规范缺少 SERVER_ADDR 后端端口约束");
            }
        }

        return blockers.isEmpty()
                ? BackendReviewResult.passed(warnings, passes)
                : BackendReviewResult.failed(blockers, warnings, passes);
    }

    private boolean requiresBackendReview(CodeGenTypeEnum targetType) {
        return targetType == CodeGenTypeEnum.BACKEND_PROJECT || targetType == CodeGenTypeEnum.FULL_STACK_PROJECT;
    }

    private boolean containsDangerousSql(String text) {
        return text.contains("drop table")
                || text.contains("drop database")
                || text.contains("truncate table")
                || text.contains("pragma writable_schema");
    }

    public record BackendReviewResult(
            boolean passed,
            List<String> blockers,
            List<String> warnings,
            List<String> passes
    ) {
        public static BackendReviewResult passed(List<String> warnings, List<String> passes) {
            return new BackendReviewResult(true, List.of(),
                    warnings == null ? List.of() : List.copyOf(warnings),
                    passes == null ? List.of() : List.copyOf(passes));
        }

        public static BackendReviewResult failed(List<String> blockers, List<String> warnings, List<String> passes) {
            return new BackendReviewResult(false,
                    blockers == null ? List.of() : List.copyOf(blockers),
                    warnings == null ? List.of() : List.copyOf(warnings),
                    passes == null ? List.of() : List.copyOf(passes));
        }
    }
}
