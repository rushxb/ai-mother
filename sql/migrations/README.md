# 数据库升级迁移规范

本目录保存面向**既有数据库**的一次性 MySQL 8.x 升级脚本。新环境仍使用
`sql/create_table.sql` 创建完整结构；已有环境不得重新执行完整初始化脚本。

## 执行规则

1. 发布前备份数据库，并在同版本预发布副本验证迁移。
2. 按文件名版本顺序执行尚未应用的脚本，不得跳序。
3. 已在任何环境执行过的迁移文件禁止修改；修正必须新增更高版本迁移。
4. 迁移失败时停止发布，不得通过删除约束、清空流水或静默修正账务数据绕过。
5. 应由部署流水线或具备 DDL 权限的迁移账号执行；应用运行账号只保留业务所需 DML 权限。
6. 执行记录至少包含版本、文件校验值、开始时间、结束时间、执行人和结果。

## 当前执行顺序

1. `V20260713__ai_model_write_integrity.sql`
2. `V20260713__chat_history_integrity.sql`
3. `V20260714__app_database_resource_integrity.sql`
4. `V20260714_1__generation_trace_integrity.sql`
5. `V20260714_2__ai_model_soft_delete_identity.sql`
6. `V20260714_3__user_credit_integrity.sql`
7. `V20260714_4__app_generation_state_ownership.sql`

> 文件名用于确定项目约定的顺序；执行前仍应根据目标环境的迁移记录确认哪些版本尚未应用。
