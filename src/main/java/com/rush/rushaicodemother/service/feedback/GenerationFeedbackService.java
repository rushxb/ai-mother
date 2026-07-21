package com.rush.rushaicodemother.service.feedback;

import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.GenerationFeedbackVO;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackCommand;

public interface GenerationFeedbackService {

    GenerationFeedbackVO submit(GenerationFeedbackCommand command, User actor);
}
