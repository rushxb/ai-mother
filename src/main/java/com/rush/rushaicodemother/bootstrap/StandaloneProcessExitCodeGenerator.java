package com.rush.rushaicodemother.bootstrap;

import org.springframework.boot.ExitCodeGenerator;

/** 标记启动后应由主进程读取退出码并立即结束的独立运行角色。 */
public interface StandaloneProcessExitCodeGenerator extends ExitCodeGenerator {
}
