package com.yupi.yuaicodemother.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 应用 Database 资源。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("app_database_resource")
public class AppDatabaseResource implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    @Column("appId")
    private Long appId;

    @Column("userId")
    private Long userId;

    @Column("resourceId")
    private String resourceId;

    @Column("resourceName")
    private String resourceName;

    @Column("databaseUrl")
    private String databaseUrl;

    @Column("dbEngine")
    private String dbEngine;

    @Column("backendRuntime")
    private String backendRuntime;

    @Column("sqlExecutionPolicy")
    private String sqlExecutionPolicy;

    private String status;

    @Column("lastUsedTime")
    private LocalDateTime lastUsedTime;

    @Column("createTime")
    private LocalDateTime createTime;

    @Column("updateTime")
    private LocalDateTime updateTime;

    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;
}
