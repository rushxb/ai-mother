package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 用户积分计费规则配置。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.user-credit")
public class UserCreditProperties {

    /** 每个积分可覆盖的 token 数。 */
    @Min(1)
    private long tokensPerCredit = 100_000L;
}
