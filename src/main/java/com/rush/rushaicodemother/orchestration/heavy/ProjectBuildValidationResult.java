package com.rush.rushaicodemother.orchestration.heavy;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.core.builder.GoBuildResult;
import com.rush.rushaicodemother.core.builder.VueBuildResult;
import com.rush.rushaicodemother.infrastructure.diagnostic.PublicDiagnosticSanitizer;

import java.util.ArrayList;
import java.util.List;

/** 跨技术栈项目质量门禁的统一公开结果。 */
public record ProjectBuildValidationResult(
        boolean success,
        String component,
        String stage,
        String projectPath,
        String summary,
        String report,
        String failureSummary
) {

    public ProjectBuildValidationResult {
        component = StrUtil.blankToDefault(component, "project");
        stage = StrUtil.blankToDefault(stage, "prepare");
        projectPath = PublicDiagnosticSanitizer.sanitizeSingleLine(projectPath, 500);
        summary = PublicDiagnosticSanitizer.sanitizeSingleLine(summary, 600);
        report = PublicDiagnosticSanitizer.sanitizeForPublicOutput(report);
        failureSummary = PublicDiagnosticSanitizer.sanitizeSingleLine(failureSummary, 1_200);
    }

    /**
 * 根据输入数据创建当前对象。
 *
 * @param result 待处理结果
 * @return 项目构建校验结果
 */
    public static ProjectBuildValidationResult fromVue(VueBuildResult result) {
        if (result == null) {
            return unavailable("frontend", "Vue 构建服务未返回结果");
        }
        return new ProjectBuildValidationResult(
                result.success(),
                "frontend",
                result.stage(),
                result.publicProjectPath(),
                result.publicSummary(),
                result.toPublicDiagnosticReport(),
                result.success() ? "" : result.toPublicFailureSummary()
        );
    }

    /**
 * 根据输入数据创建当前对象。
 *
 * @param result 待处理结果
 * @return 项目构建校验结果
 */
    public static ProjectBuildValidationResult fromGo(GoBuildResult result) {
        if (result == null) {
            return unavailable("backend", "Go 构建测试服务未返回结果");
        }
        return new ProjectBuildValidationResult(
                result.success(),
                "backend",
                result.stage(),
                result.publicProjectPath(),
                result.publicSummary(),
                result.toPublicDiagnosticReport(),
                result.success() ? "" : result.toPublicFailureSummary()
        );
    }

    /**
 * 返回全栈。
 *
 * @param projectPath 项目路径
 * @param backend 后端
 * @param frontend {@code frontend} 对应的调用参数
 * @return 项目构建校验结果
 */
    public static ProjectBuildValidationResult fullStack(
            String projectPath,
            ProjectBuildValidationResult backend,
            ProjectBuildValidationResult frontend
    ) {
        ProjectBuildValidationResult safeBackend = backend == null
                ? unavailable("backend", "Go 构建测试服务未返回结果")
                : backend;
        ProjectBuildValidationResult safeFrontend = frontend == null
                ? unavailable("frontend", "Vue 构建服务未返回结果")
                : frontend;
        boolean passed = safeBackend.success() && safeFrontend.success();

        List<String> failedComponents = new ArrayList<>(2);
        List<String> failureSummaries = new ArrayList<>(2);
        if (!safeBackend.success()) {
            failedComponents.add("backend:" + safeBackend.stage());
            failureSummaries.add("后端: " + safeBackend.failureSummary());
        }
        if (!safeFrontend.success()) {
            failedComponents.add("frontend:" + safeFrontend.stage());
            failureSummaries.add("前端: " + safeFrontend.failureSummary());
        }
        String stage = passed ? "done" : String.join(",", failedComponents);
        String summary = passed
                ? "全栈项目前后端构建验证通过"
                : "全栈项目构建验证未通过";
        String report = "[后端构建测试]\n" + safeBackend.report()
                + "\n\n[前端构建验证]\n" + safeFrontend.report();
        return new ProjectBuildValidationResult(
                passed,
                "fullstack",
                stage,
                projectPath,
                summary,
                report,
                passed ? "" : String.join("；", failureSummaries)
        );
    }

    private static ProjectBuildValidationResult unavailable(String component, String summary) {
        return new ProjectBuildValidationResult(
                false,
                component,
                "service",
                "",
                summary,
                summary,
                summary
        );
    }
}
