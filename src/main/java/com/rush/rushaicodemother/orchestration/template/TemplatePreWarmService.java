package com.rush.rushaicodemother.orchestration.template;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 模板预热服务。
 * <p>
 * 负责在模板复制后预装依赖，避免每次生成都执行 pnpm install。
 * 通过共享 node_modules 和指纹文件实现依赖复用。
 */
@Slf4j
@Service
public class TemplatePreWarmService {

    /**
     * 模板级别的 node_modules 缓存。
     * key: templateId
     * value: node_modules 路径
     */
    private final ConcurrentHashMap<String, Path> templateNodeModulesCache = new ConcurrentHashMap<>();

    /**
     * 模板级别的锁，防止并发安装。
     */
    private final ConcurrentHashMap<String, ReentrantLock> templateLocks = new ConcurrentHashMap<>();

    /**
     * 检查模板是否已预热（有 node_modules）。
     *
     * @param projectPath 项目路径
     * @return 是否已预热
     */
    public boolean isPreWarmed(String projectPath) {
        if (StrUtil.isBlank(projectPath)) {
            return false;
        }
        File nodeModules = new File(projectPath, "node_modules");
        File stampFile = new File(projectPath, ".ai-code-install.stamp");
        return nodeModules.exists() && nodeModules.isDirectory() && stampFile.exists();
    }

    /**
     * 复制预热的 node_modules 到目标目录。
     *
     * @param templateId  模板 ID
     * @param targetPath  目标路径
     * @return 是否成功
     */
    public boolean copyPreWarmedModules(String templateId, String targetPath) {
        if (StrUtil.isBlank(templateId) || StrUtil.isBlank(targetPath)) {
            return false;
        }

        // 检查目标是否已经有 node_modules
        File targetNodeModules = new File(targetPath, "node_modules");
        if (targetNodeModules.exists()) {
            log.debug("目标目录已有 node_modules: {}", targetPath);
            return true;
        }

        // 尝试从缓存获取预热的 node_modules
        Path cachedModules = templateNodeModulesCache.get(templateId);
        if (cachedModules != null && Files.exists(cachedModules)) {
            try {
                FileUtil.copyContent(cachedModules.toFile(), targetNodeModules, true);
                log.info("从缓存复制 node_modules: {} -> {}", cachedModules, targetPath);
                return true;
            } catch (Exception e) {
                log.warn("从缓存复制 node_modules 失败: {}", e.getMessage());
            }
        }

        return false;
    }

    /**
     * 记录预热的 node_modules 路径。
     *
     * @param templateId    模板 ID
     * @param nodeModulesPath node_modules 路径
     */
    public void registerPreWarmedModules(String templateId, Path nodeModulesPath) {
        if (StrUtil.isBlank(templateId) || nodeModulesPath == null) {
            return;
        }
        templateNodeModulesCache.put(templateId, nodeModulesPath);
        log.info("注册预热的 node_modules: {} -> {}", templateId, nodeModulesPath);
    }

    /**
     * 创建依赖指纹文件。
     *
     * @param projectPath    项目路径
     * @param fingerprint    指纹值
     */
    public void createStampFile(String projectPath, String fingerprint) {
        if (StrUtil.isBlank(projectPath) || StrUtil.isBlank(fingerprint)) {
            return;
        }
        try {
            File stampFile = new File(projectPath, ".ai-code-install.stamp");
            FileUtil.writeString(fingerprint, stampFile, StandardCharsets.UTF_8);
            log.debug("创建依赖指纹文件: {}", stampFile.getAbsolutePath());
        } catch (Exception e) {
            log.warn("创建依赖指纹文件失败: {}", e.getMessage());
        }
    }

    /**
     * 获取模板锁（防止并发安装）。
     *
     * @param templateId 模板 ID
     * @return 锁对象
     */
    public ReentrantLock getTemplateLock(String templateId) {
        return templateLocks.computeIfAbsent(templateId, k -> new ReentrantLock());
    }

    /**
     * 检查是否可以共享 node_modules。
     * <p>
     * 如果多个项目使用相同的模板和依赖版本，可以共享 node_modules。
     *
     * @param templateId     模板 ID
     * @param dependencyHash 依赖哈希值
     * @return 是否可以共享
     */
    public boolean canShareNodeModules(String templateId, String dependencyHash) {
        if (StrUtil.isBlank(templateId) || StrUtil.isBlank(dependencyHash)) {
            return false;
        }
        // 检查缓存中是否有匹配的 node_modules
        Path cachedModules = templateNodeModulesCache.get(templateId);
        if (cachedModules == null || !Files.exists(cachedModules)) {
            return false;
        }
        // 检查指纹是否匹配
        File stampFile = new File(cachedModules.toFile().getParent(), ".ai-code-install.stamp");
        if (!stampFile.exists()) {
            return false;
        }
        try {
            String cachedFingerprint = FileUtil.readString(stampFile, StandardCharsets.UTF_8).trim();
            return dependencyHash.equals(cachedFingerprint);
        } catch (Exception e) {
            return false;
        }
    }
}
