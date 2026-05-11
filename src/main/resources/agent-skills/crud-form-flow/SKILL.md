---
name: CRUD Form Flow
description: Keep list, search, create, edit, and delete flows stable and reusable.
keywords: CRUD,列表,表格,table,搜索,分页,新增,编辑,删除,表单,dialog,modal
modules: management,form
contextFileHints: src/views,src/pages,src/components,src/api
implementationHints: 列表字段、筛选项和操作列先定型; 新增与编辑共用表单结构; 分页和加载态必须闭环
validationHints: 验证搜索重置页码; 验证新增编辑删除后的列表刷新; 验证空数据展示
---
- 列表和表单共用同一数据模型。
- 不要把所有交互塞进单个大组件。
- 提交成功、失败和重置流程都要明确。
