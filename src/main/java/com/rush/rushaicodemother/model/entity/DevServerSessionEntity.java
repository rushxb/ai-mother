package com.rush.rushaicodemother.model.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 开发服务器会话持久化实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("dev_server_session")
public class DevServerSessionEntity {

    /** 应用编号。 */
    @Id(keyType = KeyType.None)
    private Long appId;
    /** 用户编号。 */
    private Long userId;
    /** 部署节点编号。 */
    private String nodeId;
    /** 租约持有者。 */
    private String leaseOwner;
    private String state;
    /** 服务端口。 */
    private Integer port;
    private String projectDirectory;
    private String sandboxBackend;
    private String cleanupResourceIds;
    /** 租约截止时间。 */
    private LocalDateTime leaseUntil;
    /** 最后心跳时间。 */
    private LocalDateTime heartbeatAt;
    /** 版本号。 */
    private Long version;
    private String lastError;
    /** 创建时间。 */
    private LocalDateTime createTime;
    /** 更新时间。 */
    private LocalDateTime updateTime;
}
