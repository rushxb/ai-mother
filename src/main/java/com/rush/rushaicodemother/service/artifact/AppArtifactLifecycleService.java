package com.rush.rushaicodemother.service.artifact;

import com.rush.rushaicodemother.model.entity.App;

import java.nio.file.Path;

/**
 * 应用本地产物生命周期服务。
 *
 * <p>统一管理生成目录、部署目录及目录复制，避免业务服务直接拼接或操作运行时路径。</p>
 */
public interface AppArtifactLifecycleService {

    /**
     * 获取已存在且通过安全校验的应用生成目录。
     *
     * @param app 应用
     * @return 生成目录
     */
    Path requireGeneratedDirectory(App app);

    /**
     * 将源应用生成产物完整复制到目标应用目录。
     *
     * @param sourceApp 源应用
     * @param targetApp 目标应用
     */
    void copyGeneratedArtifact(App sourceApp, App targetApp);

    /**
     * 在部署根目录中准备一个可回滚的目录替换事务。
     *
     * @param sourceDirectory 待部署的源码或构建产物目录
     * @param deployKey       部署标识
     * @return 部署目录事务
     */
    DeploymentArtifactTransaction prepareDeployment(Path sourceDirectory, String deployKey);

    /**
     * 准备可回滚的应用产物删除事务。
     *
     * @param app 待删除应用
     * @return 产物删除事务
     */
    AppArtifactDeletionTransaction prepareDeletion(App app);

    /** 删除应用生成目录。 */
    void deleteGeneratedArtifact(App app);

}
