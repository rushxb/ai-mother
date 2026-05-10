package com.yupi.yuaicodemother.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import com.yupi.yuaicodemother.model.dto.app.AppAddRequest;
import com.yupi.yuaicodemother.model.dto.app.AppCodeFileSaveRequest;
import com.yupi.yuaicodemother.model.dto.app.AppQueryRequest;
import com.yupi.yuaicodemother.model.entity.App;
import com.yupi.yuaicodemother.model.entity.User;
import com.yupi.yuaicodemother.model.vo.AppCodeFileContentVO;
import com.yupi.yuaicodemother.model.vo.AppCodeFileTreeVO;
import com.yupi.yuaicodemother.model.vo.AppDatabaseResourceVO;
import com.yupi.yuaicodemother.model.vo.AppVO;
import com.yupi.yuaicodemother.core.handler.GenerationStreamEvent;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 应用 服务层。
 *
 *
 */
public interface AppService extends IService<App> {

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
     * 创建应用
     *
     * @param appAddRequest
     * @param loginUser
     * @return
     */
    Long createApp(AppAddRequest appAddRequest, User loginUser);

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

    /**
     * 复制应用到当前用户，包含生成代码和对话历史，但不复制部署信息
     *
     * @param sourceAppId 源应用 ID
     * @param loginUser   登录用户
     * @return 新应用 ID
     */
    Long copyApp(Long sourceAppId, User loginUser);

    /**
     * 异步生成应用截图并更新封面
     *
     * @param appId  应用ID
     * @param appUrl 应用访问URL
     */
    void generateAppScreenshotAsync(Long appId, String appUrl);

    /**
     * 获取应用封装类
     *
     * @param app
     * @return
     */
    AppVO getAppVO(App app);

    /**
     * 获取应用封装类列表
     *
     * @param appList
     * @return
     */
    List<AppVO> getAppVOList(List<App> appList);

    /**
     * 构造应用查询条件
     *
     * @param appQueryRequest
     * @return
     */
    QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest);

}
