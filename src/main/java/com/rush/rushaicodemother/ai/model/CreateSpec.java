package com.rush.rushaicodemother.ai.model;

import dev.langchain4j.model.output.structured.Description;

import java.util.List;

/**
 * 统一的CREATE规范。 AI仅填充产品意图和模板变量；本地渲染器编写代码。
 */
@Description("CREATE 模式统一模板变量规格，只描述产品、模块、实体、前后端、数据库、内容和约束，不包含代码")
public record CreateSpec(
        @Description("产品定义")
        Product product,
        @Description("功能模块组合")
        List<ModuleSpec> modules,
        @Description("业务实体")
        List<EntitySpec> entities,
        @Description("前端变量")
        Frontend frontend,
        @Description("后端变量")
        Backend backend,
        @Description("数据库变量")
        Database database,
        @Description("内容变量")
        Content content,
        @Description("安全和能力约束")
        Constraints constraints
) {
    public record Product(
            String appType,
            String domain,
            String brandName,
            String audience,
            String businessGoal
    ) {
    }

    public record ModuleSpec(
            String id,
            String label,
            List<String> capabilities
    ) {
    }

    public record EntitySpec(
            String name,
            String label,
            List<FieldSpec> fields,
            List<String> relations,
            List<String> behaviors
    ) {
    }

    public record FieldSpec(
            String name,
            String type,
            String label,
            boolean required,
            List<String> options
    ) {
    }

    public record Frontend(
            String layout,
            List<String> styleKeywords,
            String density,
            List<String> componentPreference,
            List<String> interaction,
            List<String> dataViz,
            List<String> navigation,
            Theme theme
    ) {
    }

    public record Theme(
            String primary,
            String accent,
            String background,
            String radius,
            String motion
    ) {
    }

    public record Backend(
            String apiStyle,
            boolean authRequired,
            boolean pagination,
            boolean search,
            boolean sort,
            boolean softDelete,
            List<String> auditFields,
            boolean importExport,
            boolean batchActions,
            List<String> validationRules,
            String errorStyle,
            String moduleName
    ) {
    }

    public record Database(
            List<TableSpec> tables,
            List<String> indexes,
            boolean softDelete,
            String migrationStrategy
    ) {
    }

    public record TableSpec(
            String name,
            List<FieldSpec> fields,
            List<String> indexes
    ) {
    }

    public record Content(
            String tone,
            String mockDataStyle,
            List<String> menu,
            List<String> pages,
            Landing landing
    ) {
    }

    public record Landing(
            String headline,
            String description,
            String cta,
            String secondary,
            List<String> nav,
            List<Stat> stats,
            List<TextBlock> highlights,
            List<TextBlock> cases,
            List<String> process,
            List<Plan> plans,
            List<Faq> faqs,
            Contact contact
    ) {
    }

    public record Stat(String value, String label) {
    }

    public record TextBlock(String title, String text) {
    }

    public record Plan(String name, String price, String desc, List<String> features) {
    }

    public record Faq(String q, String a) {
    }

    public record Contact(String email, String phone, String address) {
    }

    public record Constraints(
            boolean noNewDependencies,
            List<String> protectedFiles,
            List<String> securityRules,
            int maxEntities,
            int maxFieldsPerEntity
    ) {
    }
}
