package com.rush.rushaicodemother.service.release;

/**
 * 串行化会改变 AI 发布身份的事务，防止多实例下证据校验与发布对象发生竞态。
 */
public interface AiReleaseCoordinationLock {

    void acquire();
}
