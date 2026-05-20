package com.rush.rushaicodemother.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.rush.rushaicodemother.annotation.AuthCheck;
import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.common.DeleteRequest;
import com.rush.rushaicodemother.common.ResultUtils;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.constant.UserConstant;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.dto.app.*;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.AppCodeFileContentVO;
import com.rush.rushaicodemother.model.vo.AppCodeFileTreeVO;
import com.rush.rushaicodemother.model.vo.AppDatabaseResourceVO;
import com.rush.rushaicodemother.model.vo.AppVO;
import com.rush.rushaicodemother.model.vo.DevServerStatusVO;
import com.rush.rushaicodemother.ratelimter.annotation.RateLimit;
import com.rush.rushaicodemother.ratelimter.enums.RateLimitType;
import com.rush.rushaicodemother.service.DevServerManager;
import com.rush.rushaicodemother.service.ProjectDownloadService;
import com.rush.rushaicodemother.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.service.AppService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 应用 控制层。
 *
 *
 */
@Slf4j
@RestController
@RequestMapping("/app")
public class AppController {

    @Resource
    private AppService appService;

    @Resource
    private UserService userService;

    @Resource
    private ProjectDownloadService projectDownloadService;

    @Resource
    private DevServerManager devServerManager;

    /**
     * 优化提示词
     *
     * @param promptOptimizeRequest 提示词优化请求
     * @param request               请求
     * @return 优化后的提示词
     */
    @PostMapping("/optimize/prompt")
    @RateLimit(limitType = RateLimitType.USER, rate = 10, rateInterval = 60, message = "提示词优化请求过于频繁，请稍后再试")
    public BaseResponse<String> optimizePrompt(@RequestBody PromptOptimizeRequest promptOptimizeRequest,
                                               HttpServletRequest request) {
        ThrowUtils.throwIf(promptOptimizeRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        String prompt = promptOptimizeRequest.getPrompt();
        ThrowUtils.throwIf(StrUtil.isBlank(prompt), ErrorCode.PARAMS_ERROR, "提示词不能为空");
        String result = appService.optimizePrompt(prompt, loginUser);
        return ResultUtils.success(result);
    }

    @PostMapping("/chat/gen/code")
    @RateLimit(limitType = RateLimitType.USER, rate = 5, rateInterval = 60, message = "AI 对话请求过于频繁，请稍后再试")
    public BaseResponse<Boolean> startChatToGenCode(@RequestBody AppChatRequest appChatRequest,
                                                    HttpServletRequest request) {
        ThrowUtils.throwIf(appChatRequest == null, ErrorCode.PARAMS_ERROR);
        Long appId = appChatRequest.getAppId();
        String message = appChatRequest.getMessage();
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 id 错误");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "提示词不能为空");
        User loginUser = userService.getLoginUser(request);
        appService.chatToGenCode(appId, message, loginUser);
        return ResultUtils.success(true);
    }

    @PostMapping("/chat/gen/code/stop")
    public BaseResponse<Boolean> stopChatToGenCode(@RequestBody AppStopRequest appStopRequest,
                                                   HttpServletRequest request) {
        ThrowUtils.throwIf(appStopRequest == null, ErrorCode.PARAMS_ERROR);
        Long appId = appStopRequest.getAppId();
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 id 错误");
        User loginUser = userService.getLoginUser(request);
        appService.stopGeneration(appId, loginUser);
        return ResultUtils.success(true);
    }

    /**
     * 启用应用 Database 服务。
     *
     * @param enableRequest 启用请求
     * @param request       请求
     * @return Database 资源
     */
    @PostMapping("/database/enable")
    public BaseResponse<AppDatabaseResourceVO> enableDatabase(@RequestBody AppDatabaseEnableRequest enableRequest,
                                                              HttpServletRequest request) {
        ThrowUtils.throwIf(enableRequest == null, ErrorCode.PARAMS_ERROR);
        Long appId = enableRequest.getAppId();
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(appService.enableDatabase(appId, loginUser));
    }

    @GetMapping(value = "/chat/gen/code", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> subscribeChatToGenCode(@RequestParam Long appId,
                                                                HttpServletRequest request) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 id 错误");
        User loginUser = userService.getLoginUser(request);
        Flux<GenerationStreamEvent> contentFlux = appService.getGenerationStream(appId, loginUser);
        return contentFlux
                .map(event -> {
                    return ServerSentEvent.<String>builder()
                            .event(event.getType())
                            .data(JSONUtil.toJsonStr(event))
                            .build();
                })
                .concatWith(Mono.just(
                        // 发送结束事件
                        ServerSentEvent.<String>builder()
                                .event("done")
                                .data("")
                                .build()
                ));
    }

    /**
     * 应用部署
     *
     * @param appDeployRequest 部署请求
     * @param request          请求
     * @return 部署 URL
     */
    @PostMapping("/deploy")
    public BaseResponse<String> deployApp(@RequestBody AppDeployRequest appDeployRequest, HttpServletRequest request) {
        // 检查部署请求是否为空
        ThrowUtils.throwIf(appDeployRequest == null, ErrorCode.PARAMS_ERROR);
        // 获取应用 ID
        Long appId = appDeployRequest.getAppId();
        // 检查应用 ID 是否为空
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);
        // 调用服务部署应用
        String deployUrl = appService.deployApp(appId, loginUser);
        // 返回部署 URL
        return ResultUtils.success(deployUrl);
    }

    /**
     * 启动应用的 Vue 开发服务器
     *
     * @param appId   应用 ID
     * @param request 请求
     * @return Dev Server 状态
     */
    @PostMapping("/dev-server/start")
    public BaseResponse<DevServerStatusVO> startDevServer(@RequestParam Long appId,
                                                          HttpServletRequest request) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        User loginUser = userService.getLoginUser(request);
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限操作该应用");
        }

        int port = devServerManager.startDevServer(app, loginUser.getId());

        // 如果应用之前没有分配端口，更新数据库
        if (app.getDevServerPort() == null || app.getDevServerPort() != port) {
            App updateApp = new App();
            updateApp.setId(appId);
            updateApp.setDevServerPort(port);
            appService.updateById(updateApp);
        }

        DevServerStatusVO statusVO = DevServerStatusVO.builder()
                .appId(appId)
                .running(true)
                .port(port)
                .previewUrl(String.format("http://localhost:%d", port))
                .status("running")
                .build();

        return ResultUtils.success(statusVO);
    }

    /**
     * 停止应用的 Vue 开发服务器
     *
     * @param appId   应用 ID
     * @param request 请求
     * @return 操作结果
     */
    @PostMapping("/dev-server/stop")
    public BaseResponse<Boolean> stopDevServer(@RequestParam Long appId,
                                               HttpServletRequest request) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        User loginUser = userService.getLoginUser(request);
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限操作该应用");
        }

        devServerManager.stopDevServer(appId, loginUser.getId());
        return ResultUtils.success(true);
    }

    /**
     * 获取应用的 Vue 开发服务器状态
     *
     * @param appId   应用 ID
     * @param request 请求
     * @return Dev Server 状态
     */
    @GetMapping("/dev-server/status")
    public BaseResponse<DevServerStatusVO> getDevServerStatus(@RequestParam Long appId,
                                                              HttpServletRequest request) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        User loginUser = userService.getLoginUser(request);
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
        }

        boolean running = devServerManager.isRunning(appId);
        Integer port = app.getDevServerPort();

        DevServerStatusVO statusVO = DevServerStatusVO.builder()
                .appId(appId)
                .running(running)
                .port(port)
                .previewUrl(port != null ? String.format("http://localhost:%d", port) : null)
                .status(running ? "running" : "stopped")
                .build();

        return ResultUtils.success(statusVO);
    }

    /**
     * 代理访问 Vue 开发服务器（保证同源以支持可视化编辑）
     * 将 /app/dev-server/proxy/{appId}/... 代理到 http://localhost:{port}/...
     */
    @RequestMapping("/dev-server/proxy/{appId}/**")
    public void proxyDevServer(@PathVariable Long appId,
                               HttpServletRequest request,
                               HttpServletResponse response) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");

        // 获取应用信息和端口
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        Integer port = app.getDevServerPort();
        ThrowUtils.throwIf(port == null, ErrorCode.NOT_FOUND_ERROR, "应用未分配 Dev Server 端口");

        // 检查 dev server 是否运行
        ThrowUtils.throwIf(!devServerManager.isRunning(appId), ErrorCode.OPERATION_ERROR, "Dev Server 未运行");

        // 提取路径：/api/app/dev-server/proxy/{appId}/xxx → /xxx
        String requestURI = request.getRequestURI();
        String prefix = String.format("/api/app/dev-server/proxy/%d", appId);
        String path = requestURI.substring(prefix.length());
        if (path.isEmpty()) {
            path = "/";
        }

        // 构建目标 URL
        String targetUrl = String.format("http://localhost:%d%s", port, path);
        String queryString = request.getQueryString();
        if (queryString != null) {
            targetUrl += "?" + queryString;
        }

        // 代理请求
        try {
            java.net.URL url = new java.net.URL(targetUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod(request.getMethod());
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(30000);

            // 复制请求头
            java.util.Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement();
                // 跳过一些不需要的头
                if (headerName.equalsIgnoreCase("host") || headerName.equalsIgnoreCase("connection")) {
                    continue;
                }
                String headerValue = request.getHeader(headerName);
                conn.setRequestProperty(headerName, headerValue);
            }

            // 如果有请求体，转发
            if ("POST".equalsIgnoreCase(request.getMethod()) || "PUT".equalsIgnoreCase(request.getMethod())) {
                conn.setDoOutput(true);
                try (java.io.InputStream is = request.getInputStream();
                     java.io.OutputStream os = conn.getOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                }
            }

            // 获取响应
            int statusCode = conn.getResponseCode();
            response.setStatus(statusCode);

            // 复制响应头
            java.util.Map<String, java.util.List<String>> headerFields = conn.getHeaderFields();
            for (java.util.Map.Entry<String, java.util.List<String>> entry : headerFields.entrySet()) {
                String key = entry.getKey();
                if (key != null) {
                    for (String value : entry.getValue()) {
                        response.addHeader(key, value);
                    }
                }
            }

            // 复制响应体
            try (java.io.InputStream is = conn.getInputStream();
                 java.io.OutputStream os = response.getOutputStream()) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
            }

        } catch (java.io.IOException e) {
            log.error("代理 Dev Server 请求失败: {}", e.getMessage());
            response.setStatus(502);
            try {
                response.getWriter().write("Dev Server 代理请求失败: " + e.getMessage());
            } catch (java.io.IOException ignored) {
            }
        }
    }

    /**
     * 获取应用代码文件树
     *
     * @param appId   应用 ID
     * @param request 请求
     * @return 文件树
     */
    @GetMapping("/code/files")
    public BaseResponse<List<AppCodeFileTreeVO>> listAppCodeFiles(@RequestParam Long appId,
                                                                  HttpServletRequest request) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(appService.listAppCodeFiles(appId, loginUser));
    }

    /**
     * 获取应用代码文件内容
     *
     * @param appId    应用 ID
     * @param filePath 文件相对路径
     * @param request  请求
     * @return 文件内容
     */
    @GetMapping("/code/file")
    public BaseResponse<AppCodeFileContentVO> getAppCodeFileContent(@RequestParam Long appId,
                                                                    @RequestParam String filePath,
                                                                    HttpServletRequest request) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(filePath), ErrorCode.PARAMS_ERROR, "文件路径不能为空");
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(appService.getAppCodeFileContent(appId, filePath, loginUser));
    }

    /**
     * 保存应用代码文件
     *
     * @param saveRequest 保存请求
     * @param request     请求
     * @return 保存结果
     */
    @PostMapping("/code/file/save")
    public BaseResponse<Boolean> saveAppCodeFile(@RequestBody AppCodeFileSaveRequest saveRequest,
                                                 HttpServletRequest request) {
        ThrowUtils.throwIf(saveRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(appService.saveAppCodeFile(saveRequest, loginUser));
    }

    /**
     * 同步当前生成代码到已部署应用
     *
     * @param appDeployRequest 同步请求
     * @param request          请求
     * @return 部署 URL
     */
    @PostMapping("/deploy/sync")
    public BaseResponse<String> syncAppDeployment(@RequestBody AppDeployRequest appDeployRequest,
                                                  HttpServletRequest request) {
        ThrowUtils.throwIf(appDeployRequest == null, ErrorCode.PARAMS_ERROR);
        Long appId = appDeployRequest.getAppId();
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        User loginUser = userService.getLoginUser(request);
        String deployUrl = appService.syncAppDeployment(appId, loginUser);
        return ResultUtils.success(deployUrl);
    }

    /**
     * 下载应用代码
     *
     * @param appId    应用ID
     * @param request  请求
     * @param response 响应
     */
    @GetMapping("/download/{appId}")
    public void downloadAppCode(@PathVariable Long appId,
                                HttpServletRequest request,
                                HttpServletResponse response) {
        // 1. 基础校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID无效");
        // 2. 查询应用信息
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 权限校验：只有应用创建者可以下载代码
        User loginUser = userService.getLoginUser(request);
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限下载该应用代码");
        }
        // 4. 构建应用代码目录路径（生成目录，非部署目录）
        String codeGenType = app.getCodeGenType();
        String sourceDirName = codeGenType + "_" + appId;
        String sourceDirPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + sourceDirName;
        // 5. 检查代码目录是否存在
        File sourceDir = new File(sourceDirPath);
        ThrowUtils.throwIf(!sourceDir.exists() || !sourceDir.isDirectory(),
                ErrorCode.NOT_FOUND_ERROR, "应用代码不存在，请先生成代码");
        // 6. 生成下载文件名（不建议添加中文内容）
        String downloadFileName = String.valueOf(appId);
        // 7. 调用通用下载服务
        projectDownloadService.downloadProjectAsZip(sourceDirPath, downloadFileName, response);
    }

    /**
     * 创建应用
     *
     * @param appAddRequest 创建应用请求
     * @param request       请求
     * @return 应用 id
     */
    @PostMapping("/add")
    public BaseResponse<Long> addApp(@RequestBody AppAddRequest appAddRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appAddRequest == null, ErrorCode.PARAMS_ERROR);
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);
        Long appId = appService.createApp(appAddRequest, loginUser);
        return ResultUtils.success(appId);
    }

    /**
     * 复制应用为当前用户自己的作品
     *
     * @param appCopyRequest 复制请求
     * @param request        请求
     * @return 新应用 id
     */
    @PostMapping("/copy")
    public BaseResponse<Long> copyApp(@RequestBody AppCopyRequest appCopyRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appCopyRequest == null || appCopyRequest.getSourceAppId() == null,
                ErrorCode.PARAMS_ERROR, "源应用 ID 不能为空");
        User loginUser = userService.getLoginUser(request);
        Long appId = appService.copyApp(appCopyRequest.getSourceAppId(), loginUser);
        return ResultUtils.success(appId);
    }

    /**
     * 更新应用（用户只能更新自己的应用名称）
     *
     * @param appUpdateRequest 更新请求
     * @param request          请求
     * @return 更新结果
     */
    @PostMapping("/update")
    public BaseResponse<Boolean> updateApp(@RequestBody AppUpdateRequest appUpdateRequest, HttpServletRequest request) {
        if (appUpdateRequest == null || appUpdateRequest.getId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String appName = StrUtil.trim(appUpdateRequest.getAppName());
        ThrowUtils.throwIf(StrUtil.isBlank(appName), ErrorCode.PARAMS_ERROR, "应用名称不能为空");
        ThrowUtils.throwIf(appName.length() > 50, ErrorCode.PARAMS_ERROR, "应用名称不能超过 50 个字符");
        User loginUser = userService.getLoginUser(request);
        long id = appUpdateRequest.getId();
        // 判断是否存在
        App oldApp = appService.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        // 仅本人可更新
        if (!oldApp.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        App app = new App();
        app.setId(id);
        app.setAppName(appName);
        // 设置编辑时间
        app.setEditTime(LocalDateTime.now());
        boolean result = appService.updateById(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 删除应用（用户只能删除自己的应用）
     *
     * @param deleteRequest 删除请求
     * @param request       请求
     * @return 删除结果
     */
    @PostMapping("/delete")
    @CacheEvict(value = "good_app_page", allEntries = true)
    public BaseResponse<Boolean> deleteApp(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        long id = deleteRequest.getId();
        // 判断是否存在
        App oldApp = appService.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        // 仅本人或管理员可删除
        if (!oldApp.getUserId().equals(loginUser.getId()) && !UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        boolean result = appService.removeById(id);
        return ResultUtils.success(result);
    }

    /**
     * 根据 id 获取应用详情
     *
     * @param id 应用 id
     * @return 应用详情
     */
    @GetMapping("/get/vo")
    public BaseResponse<AppVO> getAppVOById(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        // 查询数据库
        App app = appService.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        // 获取封装类（包含用户信息）
        return ResultUtils.success(appService.getAppVO(app));
    }

    /**
     * 分页获取当前用户创建的应用列表
     *
     * @param appQueryRequest 查询请求
     * @param request         请求
     * @return 应用列表
     */
    @PostMapping("/my/list/page/vo")
    public BaseResponse<Page<AppVO>> listMyAppVOByPage(@RequestBody AppQueryRequest appQueryRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        // 限制每页最多 20 个
        long pageSize = appQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageSize > 20, ErrorCode.PARAMS_ERROR, "每页最多查询 20 个应用");
        long pageNum = appQueryRequest.getPageNum();
        // 只查询当前用户的应用
        appQueryRequest.setUserId(loginUser.getId());
        QueryWrapper queryWrapper = appService.getQueryWrapper(appQueryRequest);
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
        // 数据封装
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        List<AppVO> appVOList = appService.getAppVOList(appPage.getRecords());
        appVOPage.setRecords(appVOList);
        return ResultUtils.success(appVOPage);
    }

    /**
     * 分页获取精选应用列表
     *
     * @param appQueryRequest 查询请求
     * @return 精选应用列表
     */
    @PostMapping("/good/list/page/vo")
    public BaseResponse<Page<AppVO>> listGoodAppVOByPage(@RequestBody AppQueryRequest appQueryRequest) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        // 限制每页最多 20 个
        long pageSize = appQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageSize > 20, ErrorCode.PARAMS_ERROR, "每页最多查询 20 个应用");
        long pageNum = appQueryRequest.getPageNum();
        // 只查询精选的应用
        appQueryRequest.setPriority(AppConstant.GOOD_APP_PRIORITY);
        QueryWrapper queryWrapper = appService.getQueryWrapper(appQueryRequest);
        // 分页查询
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
        // 数据封装
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        List<AppVO> appVOList = appService.getAppVOList(appPage.getRecords());
        appVOPage.setRecords(appVOList);
        return ResultUtils.success(appVOPage);
    }

    /**
     * 管理员删除应用
     *
     * @param deleteRequest 删除请求
     * @return 删除结果
     */
    @PostMapping("/admin/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @CacheEvict(value = "good_app_page", allEntries = true)
    public BaseResponse<Boolean> deleteAppByAdmin(@RequestBody DeleteRequest deleteRequest) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        long id = deleteRequest.getId();
        // 判断是否存在
        App oldApp = appService.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        boolean result = appService.removeById(id);
        return ResultUtils.success(result);
    }

    /**
     * 管理员更新应用
     *
     * @param appAdminUpdateRequest 更新请求
     * @return 更新结果
     */
    @PostMapping("/admin/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @CacheEvict(value = "good_app_page", allEntries = true)
    public BaseResponse<Boolean> updateAppByAdmin(@RequestBody AppAdminUpdateRequest appAdminUpdateRequest) {
        if (appAdminUpdateRequest == null || appAdminUpdateRequest.getId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        long id = appAdminUpdateRequest.getId();
        // 判断是否存在
        App oldApp = appService.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        App app = new App();
        BeanUtil.copyProperties(appAdminUpdateRequest, app);
        // 设置编辑时间
        app.setEditTime(LocalDateTime.now());
        boolean result = appService.updateById(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 管理员分页获取应用列表
     *
     * @param appQueryRequest 查询请求
     * @return 应用列表
     */
    @PostMapping("/admin/list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<AppVO>> listAppVOByPageByAdmin(@RequestBody AppQueryRequest appQueryRequest) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long pageNum = appQueryRequest.getPageNum();
        long pageSize = appQueryRequest.getPageSize();
        QueryWrapper queryWrapper = appService.getQueryWrapper(appQueryRequest);
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
        // 数据封装
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        List<AppVO> appVOList = appService.getAppVOList(appPage.getRecords());
        appVOPage.setRecords(appVOList);
        return ResultUtils.success(appVOPage);
    }

    /**
     * 管理员根据 id 获取应用详情
     *
     * @param id 应用 id
     * @return 应用详情
     */
    @GetMapping("/admin/get/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<AppVO> getAppVOByIdByAdmin(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        // 查询数据库
        App app = appService.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        // 获取封装类
        return ResultUtils.success(appService.getAppVO(app));
    }
}
