package com.rush.rushaicodemother.infrastructure.process;

import java.io.IOException;

/**
 * 进程启动器的后端能力契约。
 */
@FunctionalInterface
public interface ProcessStarter {

    Process start(ProcessBuilder processBuilder) throws IOException;
}
