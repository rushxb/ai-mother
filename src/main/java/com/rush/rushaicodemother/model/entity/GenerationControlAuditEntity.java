package com.rush.rushaicodemother.model.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 生成控制面审计事件持久化实体。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("generation_control_audit_event")
public class GenerationControlAuditEntity {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String eventId;
    private String permission;
    private String resourceType;
    private String resourceId;
    private String actorType;
    private Long actorUserId;
    private String transport;
    private String outcome;
    private String resultCode;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime createTime;
}
