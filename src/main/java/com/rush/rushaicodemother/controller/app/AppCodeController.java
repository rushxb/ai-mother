package com.rush.rushaicodemother.controller.app;

import com.rush.rushaicodemother.application.app.AppCodeDownloadApplicationService;
import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.common.ResultUtils;
import com.rush.rushaicodemother.model.dto.app.AppCodeFileSaveRequest;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.AppCodeFileContentVO;
import com.rush.rushaicodemother.model.vo.AppCodeFileTreeVO;
import com.rush.rushaicodemother.service.AppService;
import com.rush.rushaicodemother.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 应用代码文件控制器。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/app")
public class AppCodeController {

    private final AppService appService;
    private final UserService userService;
    private final AppCodeDownloadApplicationService downloadApplicationService;

    @GetMapping("/code/files")
    public BaseResponse<List<AppCodeFileTreeVO>> listAppCodeFiles(@RequestParam @Positive Long appId,
                                                                  HttpServletRequest servletRequest) {
        User loginUser = userService.getLoginUser(servletRequest);
        return ResultUtils.success(appService.listAppCodeFiles(appId, loginUser));
    }

    @GetMapping("/code/file")
    public BaseResponse<AppCodeFileContentVO> getAppCodeFileContent(
            @RequestParam @Positive Long appId,
            @RequestParam @NotBlank @Size(max = 1024) String filePath,
            HttpServletRequest servletRequest) {
        User loginUser = userService.getLoginUser(servletRequest);
        return ResultUtils.success(appService.getAppCodeFileContent(appId, filePath, loginUser));
    }

    @PostMapping("/code/file/save")
    public BaseResponse<Boolean> saveAppCodeFile(@Valid @RequestBody AppCodeFileSaveRequest request,
                                                 HttpServletRequest servletRequest) {
        User loginUser = userService.getLoginUser(servletRequest);
        return ResultUtils.success(appService.saveAppCodeFile(request, loginUser));
    }

    @GetMapping("/download/{appId}")
    public void downloadAppCode(@PathVariable @Positive Long appId,
                                HttpServletRequest servletRequest,
                                HttpServletResponse response) {
        User loginUser = userService.getLoginUser(servletRequest);
        downloadApplicationService.download(appId, loginUser, response);
    }
}