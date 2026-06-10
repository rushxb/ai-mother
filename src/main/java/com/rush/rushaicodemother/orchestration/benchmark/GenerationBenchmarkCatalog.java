package com.rush.rushaicodemother.orchestration.benchmark;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GenerationBenchmarkCatalog {

    public List<GenerationBenchmarkTask> tasks() {
        return List.of(
                new GenerationBenchmarkTask("create_vue_admin_crud", "CREATE", "vue_project", "生成商品管理后台 CRUD，包含列表、表单和搜索", "build"),
                new GenerationBenchmarkTask("create_vue_mobile_shop", "CREATE", "vue_project", "生成移动端电商商品列表和购物车", "build"),
                new GenerationBenchmarkTask("create_go_sqlite_crud", "CREATE", "backend_project", "生成 Go SQLite 用户 CRUD API", "build"),
                new GenerationBenchmarkTask("create_fullstack_crud", "CREATE", "full_stack_project", "生成 Vue + Go SQLite 商品 CRUD 全栈应用", "build"),
                new GenerationBenchmarkTask("create_landing_page", "CREATE", "vue_project", "生成 SaaS 落地页，包含 hero、功能和定价", "fast"),
                new GenerationBenchmarkTask("edit_copy", "LIGHT_EDIT", "vue_project", "把首页标题文案改为新的品牌名", "fast"),
                new GenerationBenchmarkTask("edit_style", "LIGHT_EDIT", "vue_project", "调整按钮颜色和间距", "fast"),
                new GenerationBenchmarkTask("edit_search_pagination", "AGENT_EDIT", "vue_project", "给商品表格新增搜索和分页", "build"),
                new GenerationBenchmarkTask("edit_fullstack_field_sync", "AGENT_EDIT", "full_stack_project", "新增商品 category 字段并同步前后端", "build"),
                new GenerationBenchmarkTask("edit_build_error", "AGENT_EDIT", "vue_project", "修复构建报错中的缺失 import", "build"),
                new GenerationBenchmarkTask("edit_runtime_error", "AGENT_EDIT", "vue_project", "修复预览运行时 undefined 报错", "build"),
                new GenerationBenchmarkTask("edit_delete_module", "AGENT_EDIT", "vue_project", "删除无用统计模块并清理路由引用", "build")
        );
    }
}
