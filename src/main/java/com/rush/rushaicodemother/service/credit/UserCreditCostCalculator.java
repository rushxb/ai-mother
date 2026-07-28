package com.rush.rushaicodemother.service.credit;

import com.rush.rushaicodemother.config.UserCreditProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 根据可配置计费单位计算生成任务积分成本。 */
@Component
@RequiredArgsConstructor
public class UserCreditCostCalculator {

    private final UserCreditProperties properties;

    /**
 * 计算用户额度{@code Cost}{@code Calculator}。
 *
 * @param totalTokens 总量令牌
 * @return 计算或处理后的数值结果
 */
    public long calculate(long totalTokens) {
        if (totalTokens <= 0) {
            return 0L;
        }
        long tokensPerCredit = properties.getTokensPerCredit();
        if (tokensPerCredit <= 0) {
            throw new IllegalStateException("每积分 token 数必须大于 0");
        }
        long completeCredits = totalTokens / tokensPerCredit;
        return totalTokens % tokensPerCredit == 0
                ? completeCredits
                : completeCredits + 1;
    }
}
