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
