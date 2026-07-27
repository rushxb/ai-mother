package com.rush.rushaicodemother.service;

import com.rush.rushaicodemother.model.dto.app.AppCodeFileSaveRequest;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.AppCodeFileContentVO;
import com.rush.rushaicodemother.model.vo.AppCodeFileTreeVO;
import com.rush.rushaicodemother.model.vo.AppDatabaseResourceVO;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 应用 服务层。
 *
 *
 */
public interface AppService {

    /**
     * 通过对话生成应用代码
     *
     * @param appId     应用 ID
     * @param message   提示词
     * @param loginUser 登录用户
     * @return
     */
    Flux<GenerationStreamEvent> chatToGenCode(Long appId, String message, User loginUser);

    /**
     * 提交生成任务并立即返回其稳定任务标识。
     */
    GenerationTaskResult submitGeneration(Long appId, String message, User loginUser);

    /** 使用可选的传输幂等性密钥提交。 */
    GenerationTaskResult submitGeneration(Long appId,
                                          String message,
                                          User loginUser,
                                          String idempotencyKey);

    /**
     * 订阅当前应用的生成流
     *
     * @param appId 应用 ID
     * @param loginUser 登录用户
     * @return 生成流
     */
    Flux<GenerationStreamEvent> getGenerationStream(Long appId, User loginUser);

    /**
     * 停止当前应用的生成任务
     *
     * @param appId 应用 ID
     * @param loginUser 登录用户
     */
    void stopGeneration(Long appId, User loginUser);

    /**
     * 优化用户提示词
     *
     * @param prompt 原始提示词
     * @return 优化后的提示词
     */
    String optimizePrompt(String prompt, User loginUser);

    /**
     * 应用部署
     *
     * @param appId     应用 ID
     * @param loginUser 登录用户
     * @return 可访问的部署地址
     */
    String deployApp(Long appId, User loginUser);

    /**
     * 获取应用代码文件树
     *
     * @param appId 应用 ID
     * @param loginUser 登录用户
     * @return 文件树
     */
    List<AppCodeFileTreeVO> listAppCodeFiles(Long appId, User loginUser);

    /**
     * 获取应用代码文件内容
     *
     * @param appId 应用 ID
     * @param filePath 文件相对路径
     * @param loginUser 登录用户
     * @return 文件内容
     */
    AppCodeFileContentVO getAppCodeFileContent(Long appId, String filePath, User loginUser);

    /**
     * 保存应用代码文件
     *
     * @param saveRequest 保存请求
     * @param loginUser 登录用户
     * @return 是否保存成功
     */
    Boolean saveAppCodeFile(AppCodeFileSaveRequest saveRequest, User loginUser);

    /**
     * 同步应用部署内容
     *
     * @param appId 应用 ID
     * @param loginUser 登录用户
     * @return 部署地址
     */
    String syncAppDeployment(Long appId, User loginUser);

    /**
     * 启用应用 Database 服务。
     *
     * @param appId 应用 ID
     * @param loginUser 登录用户
     * @return Database 资源信息
     */
    AppDatabaseResourceVO enableDatabase(Long appId, User loginUser);

}
