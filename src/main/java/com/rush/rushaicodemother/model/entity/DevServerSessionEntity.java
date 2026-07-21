package com.rush.rushaicodemother.model.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("dev_server_session")
public class DevServerSessionEntity {

    @Id(keyType = KeyType.None)
    private Long appId;
    private Long userId;
    private String nodeId;
    private String leaseOwner;
    private String state;
    private Integer port;
    private String projectDirectory;
    private String sandboxBackend;
    private String cleanupResourceIds;
    private LocalDateTime leaseUntil;
    private LocalDateTime heartbeatAt;
    private Long version;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
