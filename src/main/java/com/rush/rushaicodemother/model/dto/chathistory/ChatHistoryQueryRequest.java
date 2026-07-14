package com.rush.rushaicodemother.model.dto.chathistory;

import com.rush.rushaicodemother.common.PageRequest;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;

/**
 * 对话历史分页查询条件。
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class ChatHistoryQueryRequest extends PageRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Positive
    private Long id;

    @Size(max = 500)
    private String message;

    @Pattern(regexp = "user|ai", message = "消息类型仅支持 user 或 ai")
    private String messageType;

    @Positive
    private Long appId;

    @Positive
    private Long userId;

}
