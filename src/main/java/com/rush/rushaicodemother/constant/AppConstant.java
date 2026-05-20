package com.rush.rushaicodemother.constant;

/**
 * 应用常量
 */
public interface AppConstant {

    /**
     * 应用基础目录
     * 可通过 JVM 参数覆盖：-Dcode.base-dir=/root/ai-code
     */
    String APP_BASE_DIR = resolveRuntimePath("code.base-dir", System.getProperty("user.dir"));

    /**
     * 临时文件根目录
     * 可通过 JVM 参数覆盖：-Dcode.tmp-root-dir=/root/ai-code/tmp
     */
    String TMP_ROOT_DIR = resolveRuntimePath("code.tmp-root-dir", APP_BASE_DIR + "/tmp");

    /**
     * 追加到模型输入中的项目上下文标记
     */
    String PROJECT_CONTEXT_MARKER = "【当前项目上下文】";

    /**
     * 精选应用的优先级
     */
    Integer GOOD_APP_PRIORITY = 99;

    /**
     * 默认应用优先级
     */
    Integer DEFAULT_APP_PRIORITY = 0;

    /**
     * 创建应用阶段
     */
    String GENERATING_STAGE_CREATE = "create";

    /**
     * 改修应用阶段
     */
    String GENERATING_STAGE_UPDATE = "update";

    /**
     * 后台构建阶段
     */
    String GENERATING_STAGE_BUILD = "build";

    /**
     * 后台自动修复阶段
     */
    String GENERATING_STAGE_REPAIR = "repair";

    /**
     * 智能体编排阶段
     */
    String GENERATING_STAGE_AGENT = "agent";

    /**
     * 应用生成目录
     */
    String CODE_OUTPUT_ROOT_DIR = resolveRuntimePath("code.output-root-dir", TMP_ROOT_DIR + "/code_output");

    /**
     * 应用部署目录
     */
    String CODE_DEPLOY_ROOT_DIR = resolveRuntimePath("code.deploy-root-dir", TMP_ROOT_DIR + "/code_deploy");

    /**
     * 应用代码快照目录
     */
    String CODE_SNAPSHOT_ROOT_DIR = resolveRuntimePath("code.snapshot-root-dir", TMP_ROOT_DIR + "/code_snapshot");

    /**
     * 生成编排任务快照目录
     */
    String ORCHESTRATION_TASK_ROOT_DIR = resolveRuntimePath("code.orchestration-task-root-dir", TMP_ROOT_DIR + "/orchestration_tasks");

    /**
     * 截图临时目录
     */
    String SCREENSHOT_ROOT_DIR = resolveRuntimePath("code.screenshot-root-dir", TMP_ROOT_DIR + "/screenshots");

    /**
     * 应用部署域名
     */
    String CODE_DEPLOY_HOST = "http://localhost:8088";

    static String resolveRuntimePath(String key, String defaultValue) {
        String overrideValue = System.getProperty(key);
        if (overrideValue == null || overrideValue.isBlank()) {
            return defaultValue;
        }
        return overrideValue.trim();
    }
}
