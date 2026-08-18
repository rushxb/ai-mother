package com.rush.rushaicodemother.orchestration.edit.fallback;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 编辑回退候选协议适配器。
 *
 * <p>实现负责声明适用的工程类型，并按对应生态与工作区角色根给出候选绝对路径；
 * 路径安全校验、canonical-relative 换算和去重由 resolver 统一负责。</p>
 */
public interface EditFallbackCandidateAdapter {

    /** 返回当前 adapter 可贡献回退候选的工程类型。 */
    Set<CodeGenTypeEnum> supportedCodeGenTypes();

    /** 返回当前工程工作区中的候选入口路径，路径无需预先存在。 */
    List<Path> candidatePaths(GenerationWorkspace workspace);
}
