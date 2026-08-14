package com.rush.rushaicodemother.service.aimodel;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.model.StreamingModelFactory;
import com.rush.rushaicodemother.model.enums.ModelInvocationBillingMode;
import com.rush.rushaicodemother.model.enums.ModelInvocationPurpose;
import com.rush.rushaicodemother.model.vo.AiModelConnectionTestResultVO;
import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 以关闭请求/响应日志的方式执行模型连接探测。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiModelConnectionTester {

    private final StreamingModelFactory streamingModelFactory;

    /**
 * 返回{@code test}。
 *
 * @param configuration 配置
 * @param operatorUserId 执行连接探测的管理员
 * @return AI 模型连接{@code Tester}
 */
    public AiModelConnectionTestResultVO test(AiModelRuntimeConfiguration configuration,
                                               long operatorUserId) {
        if (operatorUserId <= 0) {
            throw new IllegalArgumentException("operator user ID must be positive");
        }
        MonitorContext previousContext = MonitorContextHolder.getContext();
        try {
            MonitorContextHolder.setContext(MonitorContext.builder()
                    .userId(Long.toString(operatorUserId))
                    .taskId("connection-test:" + UUID.randomUUID())
                    .invocationPurpose(ModelInvocationPurpose.CONNECTION_TEST)
                    .billingMode(ModelInvocationBillingMode.EXEMPT)
                    .billingExemptionReason("admin_connectivity_probe")
                    .build());
            String response = streamingModelFactory.testConnection(configuration);
            log.info("模型连接测试成功，provider={}，modelId={}",
                    configuration.provider(), configuration.modelId());
            return AiModelConnectionTestResultVO.builder()
                    .success(StrUtil.isNotBlank(response))
                    .message("模型连接测试成功")
                    .build();
        } catch (Exception exception) {
            String safeMessage = AiModelConnectionErrorMessageResolver.resolve(exception);
            log.warn("模型连接测试失败，provider={}，modelId={}，errorType={}，message={}",
                    configuration.provider(), configuration.modelId(),
                    exception.getClass().getSimpleName(), safeMessage);
            return AiModelConnectionTestResultVO.builder()
                    .success(false)
                    .message(safeMessage)
                    .build();
        } finally {
            if (previousContext == null) {
                MonitorContextHolder.clearContext();
            } else {
                MonitorContextHolder.setContext(previousContext);
            }
        }
    }
}
