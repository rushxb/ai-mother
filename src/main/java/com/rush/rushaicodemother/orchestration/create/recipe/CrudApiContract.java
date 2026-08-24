package com.rush.rushaicodemother.orchestration.create.recipe;

import cn.hutool.core.util.StrUtil;

import java.util.regex.Pattern;

/**
 * 快速生成前后端共享的 CRUD HTTP 契约。
 *
 * <p>后端路由和前端客户端必须从同一事实生成，禁止各自拼接路径后逐步漂移。</p>
 */
record CrudApiContract(String collectionPath, String listPath, boolean paginated) {

    private static final Pattern TABLE_NAME = Pattern.compile("[a-z][a-z0-9_]*");

    CrudApiContract {
        if (StrUtil.isBlank(collectionPath) || !collectionPath.startsWith("/")) {
            throw new IllegalArgumentException("CRUD 集合路径必须以 / 开头");
        }
        if (StrUtil.isBlank(listPath) || !listPath.startsWith(collectionPath + "/")) {
            throw new IllegalArgumentException("CRUD 列表路径必须属于集合路径");
        }
    }

    static CrudApiContract fromTable(String tableName, boolean paginated) {
        String normalizedTable = StrUtil.blankToDefault(tableName, "").strip();
        if (!TABLE_NAME.matcher(normalizedTable).matches()) {
            throw new IllegalArgumentException("CRUD 表名不合法：" + normalizedTable);
        }
        String collectionPath = "/" + normalizedTable.replace('_', '-');
        String listPath = collectionPath + (paginated ? "/list/page" : "/list");
        return new CrudApiContract(collectionPath, listPath, paginated);
    }
}
