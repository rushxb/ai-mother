package com.rush.rushaicodemother.service.workspace;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.vo.AppCodeFileContentVO;
import com.rush.rushaicodemother.model.vo.AppCodeFileTreeVO;

import java.util.List;

/**
 * 应用代码工作区服务。
 *
 * <p>调用方负责应用访问权限校验，本服务负责受控工作区内的文件读取、写入和构建回滚。</p>
 */
public interface AppCodeWorkspaceService {

    /**
     * 列出工作区中允许向用户展示的文件树。
     *
     * @param app 已完成访问授权的应用
     * @return 文件树；代码尚未生成时返回空列表
     */
    List<AppCodeFileTreeVO> listFiles(App app);

    /**
     * 读取工作区内的可编辑文本文件。
     *
     * @param app      已完成访问授权的应用
     * @param filePath 相对工作区根目录的文件路径
     * @return 文件内容视图
     */
    AppCodeFileContentVO readFile(App app, String filePath);

    /**
     * 原子保存工作区文件，并在需要时执行构建校验和失败回滚。
     *
     * @param app      已完成访问授权的应用
     * @param filePath 相对工作区根目录的文件路径
     * @param content  UTF-8 文件内容
     */
    void saveFile(App app, String filePath, String content);
}