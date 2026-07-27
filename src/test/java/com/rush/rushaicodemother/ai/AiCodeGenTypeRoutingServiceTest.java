package com.rush.rushaicodemother.ai;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Tag("external")
public class AiCodeGenTypeRoutingServiceTest {

    private static final Logger LOG = LoggerFactory.getLogger(AiCodeGenTypeRoutingServiceTest.class);

    @Resource
    private AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService;

    @Test
    public void testRouteCodeGenType() {
        String userPrompt = "做一个简单的个人介绍页面";
        CodeGenTypeEnum result = aiCodeGenTypeRoutingService.routeCodeGenType(userPrompt);
        LOG.info("用户需求: {} -> {}", userPrompt, result.getValue());
        userPrompt = "做一个公司官网，需要首页、关于我们、联系我们三个页面";
        result = aiCodeGenTypeRoutingService.routeCodeGenType(userPrompt);
        LOG.info("用户需求: {} -> {}", userPrompt, result.getValue());
        userPrompt = "做一个电商管理系统，包含用户管理、商品管理、订单管理，需要路由和状态管理";
        result = aiCodeGenTypeRoutingService.routeCodeGenType(userPrompt);
        LOG.info("用户需求: {} -> {}", userPrompt, result.getValue());
    }
}
