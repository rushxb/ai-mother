package com.rush.rushaicodemother.orchestration.create.recipe;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.rush.rushaicodemother.orchestration.create.recipe.RecipeSpecSupport.normalizeFieldType;
import static com.rush.rushaicodemother.orchestration.create.recipe.RecipeValueSupport.*;

/** Renders typed admin data modules and deterministic mock records. */
@Component
final class AdminDataTemplates {

    String adminData(AdminRecipe recipe) {
        String dashboardClass = "dashboard-" + recipe.frontend().density() + " " + String.join(" ", recipe.frontend().styleClasses());
        return """
                import type { DashboardMetrics, OrderInfo } from '@/types'

                export interface AdminSite {
                  brand: string
                  slogan: string
                  nav: AdminNavItem[]
                }

                export interface AdminNavItem {
                  label: string
                  path: string
                  icon?: string
                  children?: AdminNavItem[]
                }

                export const site: AdminSite = {
                  brand: '%s',
                  slogan: '%s数字化运营工作台',
                  nav: %s
                }

                export const dashboardClass = '%s'
                export const enabledInteractions = %s
                export const filterChips = %s
                export const visualizationBlocks = %s

                export const metrics: DashboardMetrics[] = [
                  { label: '活跃%s', value: '1,286', trend: '+12.6%%', trendType: 'up' },
                  { label: '待处理事项', value: '84', trend: '+8.2%%', trendType: 'up' },
                  { label: '本月转化', value: '32.4%%', trend: '+4.1%%', trendType: 'up' },
                  { label: '异常预警', value: '7', trend: '-2.0%%', trendType: 'down' }
                ]

                export const orders: OrderInfo[] = [
                %s
                ]

                export const activities: string[] = [
                  '%s数据同步完成，新增 42 条记录',
                  '运营负责人更新了%s状态',
                  '系统生成了%s周报',
                  '新的%s审核任务已分配'
                ]
                """.formatted(
                escape(recipe.brand()),
                escape(recipe.domain()),
                adminNavItems(recipe),
                escape(dashboardClass),
                tsStringArray(recipe.frontend().interactions()),
                tsStringArray(filterChips(recipe)),
                visualizationBlocks(recipe),
                escape(recipe.entityLabel()),
                adminRows(recipe),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel())
        );
    }

    String tableColumns(AdminRecipe recipe) {
        List<RecipeField> fields = recipe.fields().stream().limit(4).toList();
        StringBuilder builder = new StringBuilder("export const columns = [\n");
        builder.append("  { key: 'no', title: '编号' },\n");
        for (RecipeField field : fields) {
            builder.append("  { key: '").append(camel(field.name())).append("', title: '").append(escape(field.label())).append("'");
            if (recipe.frontend().interactions().contains("sort")) {
                builder.append(", sortable: true");
            }
            builder.append(" },\n");
        }
        builder.append("  { key: 'status', title: '状态'");
        if (recipe.frontend().interactions().contains("filter")) {
            builder.append(", filterable: true");
        }
        builder.append(" }\n");
        builder.append("]\n");
        return builder.toString();
    }

    String sidebarMenu(AdminRecipe recipe) {
        String items = recipe.frontend().navigation().isEmpty()
                ? """
                  { label: '总览', path: '#overview', icon: 'dashboard' },
                  { label: '%s管理', path: '#records', icon: 'table' },
                  { label: '数据分析', path: '#analytics', icon: 'chart' },
                  { label: '系统设置', path: '#settings', icon: 'settings' }
                """.formatted(escape(recipe.entityLabel()))
                : recipe.frontend().navigation().stream()
                .limit(6)
                .map(label -> "  { label: '" + escape(label) + "', path: '#" + lowerIdentifier(label) + "', icon: 'menu' }")
                .collect(java.util.stream.Collectors.joining(",\n"));
        return """
                export const sidebarMenu = [
                %s
                ]
                """.formatted(items);
    }

    String statistics(AdminRecipe recipe) {
        String third = recipe.frontend().dataViz().contains("funnel") ? "漏斗转化" : "待处理";
        String fourth = recipe.frontend().dataViz().contains("ranking") ? "TOP 排名" : "完成率";
        return """
                export const statistics = [
                  { label: '%s总量', value: '1,286', trend: '+12.6%%', tone: 'green' },
                  { label: '本周新增', value: '148', trend: '+8.2%%', tone: 'blue' },
                  { label: '%s', value: '84', trend: '-3.1%%', tone: 'amber' },
                  { label: '%s', value: '92.4%%', trend: '+4.1%%', tone: 'violet' }
                ]
                """.formatted(escape(recipe.entityLabel()), escape(third), escape(fourth));
    }

    String operationsData(AdminRecipe recipe) {
        String first = recipe.frontend().interactions().contains("batch") ? "批量待处理" : "待审核";
        String second = recipe.frontend().interactions().contains("export") ? "待导出数据" : "进行中任务";
        return """
                export const operationQueues = [
                  { label: '%s%s', value: 18, tone: 'warning' },
                  { label: '%s', value: 42, tone: 'default' },
                  { label: '异常预警', value: 7, tone: 'danger' }
                ]
                """.formatted(escape(first), escape(recipe.entityLabel()), escape(second));
    }

    String activityData(AdminRecipe recipe) {
        String secondTitle = recipe.frontend().interactions().contains("drawer") ? "详情抽屉打开" : "状态变更";
        String thirdTitle = recipe.frontend().dataViz().contains("ranking") ? "排行刷新" : "周报生成";
        return """
                export const activities = [
                  { time: '09:00', title: '%s同步完成', description: '同步 128 条%s记录' },
                  { time: '10:30', title: '%s', description: '运营负责人更新了 12 条记录' },
                  { time: '14:00', title: '%s', description: '%s运营周报已生成' }
                ]
                """.formatted(escape(recipe.entityLabel()), escape(recipe.entityLabel()), escape(secondTitle), escape(thirdTitle), escape(recipe.domain()));
    }

    private List<String> filterChips(AdminRecipe recipe) {
        List<String> chips = new ArrayList<>();
        chips.add(recipe.entityLabel() + "状态");
        chips.add(recipe.domain() + "区域");
        if (recipe.frontend().interactions().contains("drawer")) {
            chips.add("详情抽屉");
        }
        if (recipe.frontend().interactions().contains("batch")) {
            chips.add("批量操作");
        }
        return chips;
    }

    private String adminNavItems(AdminRecipe recipe) {
        List<String> nav = recipe.frontend().navigation().isEmpty()
                ? List.of("工作台", recipe.entityLabel() + "管理", "数据分析", "系统设置")
                : recipe.frontend().navigation();
        String items = nav.stream().limit(6)
                .map(label -> "    { label: '" + escape(label) + "', path: '/" + lowerIdentifier(label) + "', icon: 'Menu' }")
                .collect(java.util.stream.Collectors.joining(",\n"));
        return "[\n" + items + "\n  ]";
    }

    private String tsStringArray(List<String> values) {
        return "[" + values.stream().map(value -> "'" + escape(value) + "'").collect(java.util.stream.Collectors.joining(", ")) + "]";
    }

    private String visualizationBlocks(AdminRecipe recipe) {
        List<String> blocks = new ArrayList<>();
        for (String viz : recipe.frontend().dataViz()) {
            switch (viz) {
                case "ranking" -> blocks.add("{ type: 'ranking', title: '" + escape(recipe.entityLabel()) + "排行', range: '本周', values: [88, 76, 69, 61, 52] }");
                case "funnel" -> blocks.add("{ type: 'funnel', title: '转化漏斗', range: '本月', values: [96, 78, 52, 31, 18] }");
                case "calendar" -> blocks.add("{ type: 'calendar', title: '日程热度', range: '近 30 日', values: [32, 48, 73, 45, 67, 82, 58] }");
                default -> blocks.add("{ type: 'trend', title: '业务趋势', range: '近 7 日', values: [42, 68, 54, 88, 74, 96, 82] }");
            }
        }
        return "[\n  " + blocks.stream().distinct().limit(3).collect(java.util.stream.Collectors.joining(",\n  ")) + "\n]";
    }

    private String adminRows(AdminRecipe recipe) {
        List<String> rows = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            StringBuilder row = new StringBuilder("  { no: 'REC-00").append(i).append("'");
            for (RecipeField field : recipe.fields().stream().limit(4).toList()) {
                row.append(", ").append(camel(field.name())).append(": ").append(tsValue(field, i, recipe));
            }
            row.append(", status: '").append(List.of("已完成", "进行中", "待处理", "已完成").get(i - 1)).append("'");
            row.append(", amount: ").append(List.of(12800, 26800, 6900, 52000).get(i - 1));
            row.append(", createTime: '2026-06-10 ").append(List.of("09:30:00", "10:15:00", "11:05:00", "13:20:00").get(i - 1)).append("' }");
            rows.add(row.toString());
        }
        return String.join(",\n", rows);
    }

    private String tsValue(RecipeField field, int index, AdminRecipe recipe) {
        return switch (normalizeFieldType(field.type())) {
            case "integer" -> String.valueOf(20 + index * 8);
            case "decimal" -> String.valueOf(99.0 + index * 30);
            case "boolean" -> index % 2 == 0 ? "true" : "false";
            case "datetime" -> "'2026-06-" + (10 + index) + " 09:00:00'";
            case "enum" -> "'" + (index % 2 == 0 ? "上架" : "下架") + "'";
            default -> "'" + escape(recipe.entityLabel()) + field.label() + index + "'";
        };
    }
}
