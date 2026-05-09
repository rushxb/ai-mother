package com.yupi.yuaicodemother.constant;

/**
 * 应用常量
 */
public interface AppConstant {

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
     * 应用生成目录
     */
    String CODE_OUTPUT_ROOT_DIR = System.getProperty("user.dir") + "/tmp/code_output";

    /**
     * 应用部署目录
     */
    String CODE_DEPLOY_ROOT_DIR = System.getProperty("user.dir") + "/tmp/code_deploy";

    /**
     * 应用部署域名
     */
    String CODE_DEPLOY_HOST = "http://localhost:8088";
}
