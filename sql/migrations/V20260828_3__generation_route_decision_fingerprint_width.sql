-- routeDecisionVersion 持久化的是 SHA-256 发布指纹，必须完整容纳 64 个十六进制字符。
ALTER TABLE generation_task
    MODIFY COLUMN routeDecisionVersion varchar(64) NULL COMMENT '路由决策发布指纹';
