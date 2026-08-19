package com.rush.rushaicodemother.orchestration.workspace.layout;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

import java.nio.file.Path;
import java.util.Set;

/**
 * 单类工程布局的扩展接口。
 *
 * <p>新增工程类型时注册新的 adapter 即可，无需修改工作区解析主流程。</p>
 */
public interface GenerationWorkspaceLayoutAdapter {

    /** 返回该 adapter 唯一负责的工程类型。 */
    Set<CodeGenTypeEnum> supportedTypes();

    /** 根据规范工作区根目录解析角色根目录。 */
    GenerationWorkspaceLayout resolve(Path canonicalRootPath);
}
