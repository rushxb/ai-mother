package com.rush.rushaicodemother.service.feedback;

import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.GenerationFeedbackVO;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackCommand;

/**
 * 生成反馈服务契约。
 */
public interface GenerationFeedbackService {

    GenerationFeedbackVO submit(GenerationFeedbackCommand command, User actor);
}
