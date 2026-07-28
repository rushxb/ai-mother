package com.rush.rushaicodemother;

import com.rush.rushaicodemother.bootstrap.StandaloneProcessExitCodeGenerator;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * RushAI 代码母体应用启动入口。
 */
@EnableCaching
@SpringBootApplication
@MapperScan("com.rush.rushaicodemother.mapper")
public class RushAiCodeMotherApplication {

    /**
 * 启动 Spring Boot 后端应用，并在启动失败时返回非零退出码。
 *
 * @param args 命令行参数
 */
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(
                RushAiCodeMotherApplication.class, args);
        StandaloneProcessExitCodeGenerator exitCodeGenerator = context.getBeanProvider(
                StandaloneProcessExitCodeGenerator.class).getIfAvailable();
        if (exitCodeGenerator != null) {
            int exitCode = SpringApplication.exit(context, exitCodeGenerator);
            System.exit(exitCode);
        }
    }

}
