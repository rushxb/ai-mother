package com.rush.rushaicodemother.orchestration.dag;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.constant.AppConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * 本地任务快照存储。
 * 后续可以替换为数据库表，编排层调用方不需要改变。
 */
@Slf4j
@Component
public class GenerationOrchestrationTaskStore {

    public GenerationOrchestrationTask create(Long appId, String userMessage) {
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId(IdUtil.fastSimpleUUID());
        task.setAppId(appId);
        task.setRequestHash(buildRequestHash(userMessage));
        task.setStatus("running");
        save(task);
        return task;
    }

    public void save(GenerationOrchestrationTask task) {
        if (task == null || StrUtil.isBlank(task.getTaskId())) {
            return;
        }
        task.setUpdatedAt(LocalDateTime.now());
        try {
            File file = resolveTaskFile(task.getAppId(), task.getTaskId());
            FileUtil.mkParentDirs(file);
            FileUtil.writeString(JSONUtil.toJsonPrettyStr(task), file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("保存编排任务快照失败，taskId: {}", task.getTaskId(), e);
        }
    }

    public File resolveTaskFile(Long appId, String taskId) {
        return new File(AppConstant.ORCHESTRATION_TASK_ROOT_DIR
                + File.separator + "app_" + appId
                + File.separator + taskId + ".json");
    }

    private String buildRequestHash(String userMessage) {
        return DigestUtil.md5Hex(StrUtil.blankToDefault(userMessage, ""));
    }
}
