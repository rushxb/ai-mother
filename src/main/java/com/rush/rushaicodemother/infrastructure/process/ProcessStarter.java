package com.rush.rushaicodemother.infrastructure.process;

import java.io.IOException;

@FunctionalInterface
public interface ProcessStarter {

    Process start(ProcessBuilder processBuilder) throws IOException;
}
