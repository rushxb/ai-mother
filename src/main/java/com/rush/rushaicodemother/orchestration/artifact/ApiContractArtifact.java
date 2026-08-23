package com.rush.rushaicodemother.orchestration.artifact;

import com.rush.rushaicodemother.orchestration.intent.IntentBusinessDomain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Planner 生成的 API 字段契约。
 *
 * <p>该类型集中拥有嵌套字段结构、前后端字段来源、模块路径和标准 CRUD 端点之间的
 * 一致性规则。恢复检查点时必须先还原为本类型，不能仅凭非空 Map 认定契约有效。</p>
 */
public final class ApiContractArtifact {

    public static final String KEY = "api_contract";

    private static final String ROLE = "Planner";
    private static final String TITLE = "API 字段契约";
    private static final String CONTRACT_VERSION = "v1";
    private static final String API_PREFIX = "/api";
    private static final String SOURCE_PLANNER = "planner";
    private static final String SOURCE_FRONTEND_FIRST_UPGRADE = "frontend_first_upgrade";
    private static final String FIELD_SOURCE_REQUIREMENT = "user_requirement_first";
    private static final String FIELD_SOURCE_FRONTEND = "existing_frontend_reverse_extract";
    private static final Pattern MODULE_NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern ENTITY_NAME = Pattern.compile("[A-Z][A-Za-z0-9]{0,63}");
    private static final Pattern TABLE_NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final List<String> DEFAULT_NOTES = List.of(
            "首阶段只生成最小契约骨架，具体字段由 CREATE spec 和本地 recipe 从用户需求中补齐",
            "从前端升级全栈时，优先从现有 API 调用、mock 数据、表单字段、表格列反推字段契约",
            "字段命名需同步覆盖 frontend DTO、backend request/response、repository scan 和 SQLite schema"
    );

    private final String source;
    private final String userMessage;
    private final ApiDomain domain;
    private final List<String> notes;

    private ApiContractArtifact(
            String source,
            String userMessage,
            ApiDomain domain,
            List<String> notes
    ) {
        this.source = requireSource(source);
        this.userMessage = requireText(userMessage, "userMessage");
        this.domain = Objects.requireNonNull(domain, "API 字段契约领域不能为空");
        this.notes = requireStringList(notes, "notes", false);
    }

    /** 根据规划场景和领域推断创建规范化 API 契约。 */
    public static ApiContractArtifact create(
            boolean frontendFirstUpgrade,
            String userMessage,
            IntentBusinessDomain businessDomain
    ) {
        return create(frontendFirstUpgrade, userMessage, domainFor(businessDomain));
    }

    /** 根据已经确定的领域结构创建规范化 API 契约。 */
    public static ApiContractArtifact create(
            boolean frontendFirstUpgrade,
            String userMessage,
            ApiDomain domain
    ) {
        return new ApiContractArtifact(
                frontendFirstUpgrade ? SOURCE_FRONTEND_FIRST_UPGRADE : SOURCE_PLANNER,
                userMessage,
                domain,
                DEFAULT_NOTES
        );
    }

    /** 将冻结的意图领域映射为 API 技术契约；Agent 节点不再持有字段模板。 */
    private static ApiDomain domainFor(IntentBusinessDomain businessDomain) {
        IntentBusinessDomain safeDomain = businessDomain == null
                ? IntentBusinessDomain.GENERAL
                : businessDomain;
        return switch (safeDomain) {
            case PRODUCT -> new ApiDomain(
                    "product",
                    "Product",
                    "products",
                    List.of(
                            field("id", "int64", "integer", "主键"),
                            field("name", "string", "text", "名称"),
                            field("price", "float64", "real", "价格"),
                            field("description", "string", "text", "描述"),
                            field("createdAt", "time.Time", "timestamp", "创建时间"),
                            field("updatedAt", "time.Time", "timestamp", "更新时间")
                    )
            );
            case ORDER -> new ApiDomain(
                    "order",
                    "Order",
                    "orders",
                    List.of(
                            field("id", "int64", "integer", "主键"),
                            field("orderNo", "string", "text", "订单号"),
                            field("status", "string", "text", "状态"),
                            field("amount", "float64", "real", "金额"),
                            field("createdAt", "time.Time", "timestamp", "创建时间"),
                            field("updatedAt", "time.Time", "timestamp", "更新时间")
                    )
            );
            case TASK -> new ApiDomain(
                    "task",
                    "Task",
                    "tasks",
                    List.of(
                            field("id", "int64", "integer", "主键"),
                            field("title", "string", "text", "标题"),
                            field("status", "string", "text", "状态"),
                            field("priority", "string", "text", "优先级"),
                            field("createdAt", "time.Time", "timestamp", "创建时间"),
                            field("updatedAt", "time.Time", "timestamp", "更新时间")
                    )
            );
            case GENERAL -> new ApiDomain(
                    "app",
                    "AppItem",
                    "app_items",
                    List.of(
                            field("id", "int64", "integer", "主键"),
                            field("name", "string", "text", "名称"),
                            field("status", "string", "text", "状态"),
                            field("createdAt", "time.Time", "timestamp", "创建时间"),
                            field("updatedAt", "time.Time", "timestamp", "更新时间")
                    )
            );
        };
    }

    private static ApiField field(
            String jsonName,
            String goType,
            String sqliteType,
            String description
    ) {
        return new ApiField(jsonName, goType, sqliteType, description);
    }

    /** 从持久制品恢复并重新验证全部嵌套事实。 */
    public static ApiContractArtifact fromArtifact(GenerationArtifact artifact) {
        if (artifact == null) {
            throw invalidField("artifact", "不能为空");
        }
        if (!KEY.equals(artifact.key())) {
            throw invalidField("key", "制品类型不匹配: " + artifact.key());
        }
        Map<String, Object> payload = requireMap(artifact.payload(), "payload");
        String source = requireSource(requireText(payload.get("source"), "source"));
        String userMessage = requireText(payload.get("userMessage"), "userMessage");
        Map<String, Object> contract = requireMap(payload.get("contract"), "contract");

        requireExactText(contract, "version", CONTRACT_VERSION);
        requireExactText(contract, "apiPrefix", API_PREFIX);
        String moduleName = requireIdentifier(
                contract.get("moduleName"), "contract.moduleName", MODULE_NAME);
        requireExactText(
                contract,
                "moduleDirectory",
                "internal/modules/" + moduleName
        );
        requireExactText(
                contract,
                "fieldSource",
                SOURCE_FRONTEND_FIRST_UPGRADE.equals(source)
                        ? FIELD_SOURCE_FRONTEND
                        : FIELD_SOURCE_REQUIREMENT
        );

        Map<String, Object> entity = requireSingleMap(
                contract.get("entities"), "contract.entities");
        String entityName = requireIdentifier(
                entity.get("name"), "contract.entities.name", ENTITY_NAME);
        List<ApiField> entityFields = requireFields(
                entity.get("fields"), "contract.entities.fields");

        Map<String, Object> schemaTable = requireSingleMap(
                contract.get("schemaTables"), "contract.schemaTables");
        String tableName = requireIdentifier(
                schemaTable.get("name"), "contract.schemaTables.name", TABLE_NAME);
        List<ApiField> tableFields = requireFields(
                schemaTable.get("fields"), "contract.schemaTables.fields");
        if (!entityFields.equals(tableFields)) {
            throw invalidField("contract.schemaTables.fields", "必须与实体字段完全一致");
        }

        List<ApiEndpoint> actualEndpoints = requireEndpoints(contract.get("endpoints"));
        List<ApiEndpoint> expectedEndpoints = standardEndpoints(moduleName);
        if (!actualEndpoints.equals(expectedEndpoints)) {
            throw invalidField("contract.endpoints", "必须与模块的标准 CRUD 端点一致");
        }

        List<String> notes = requireStringList(contract.get("notes"), "contract.notes", false);
        return new ApiContractArtifact(
                source,
                userMessage,
                new ApiDomain(moduleName, entityName, tableName, entityFields),
                notes
        );
    }

    public String source() {
        return source;
    }

    public String userMessage() {
        return userMessage;
    }

    public ApiDomain domain() {
        return domain;
    }

    /** 返回供代码生成 Prompt 使用的稳定嵌套契约。 */
    public Map<String, Object> contractPayload() {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("version", CONTRACT_VERSION);
        contract.put("apiPrefix", API_PREFIX);
        contract.put("moduleDirectory", "internal/modules/" + domain.moduleName());
        contract.put("fieldSource", fieldSource());
        contract.put("moduleName", domain.moduleName());
        contract.put("entities", List.of(entityPayload()));
        contract.put("endpoints", standardEndpoints(domain.moduleName()).stream()
                .map(ApiContractArtifact::endpointPayload)
                .toList());
        contract.put("schemaTables", List.of(schemaTablePayload()));
        contract.put("notes", notes);
        return Collections.unmodifiableMap(contract);
    }

    /** 转换为统一 key、角色和标题的持久制品。 */
    public GenerationArtifact toArtifact() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", source);
        payload.put("userMessage", userMessage);
        payload.put("contract", contractPayload());
        return GenerationArtifact.of(KEY, ROLE, TITLE, payload);
    }

    private String fieldSource() {
        return SOURCE_FRONTEND_FIRST_UPGRADE.equals(source)
                ? FIELD_SOURCE_FRONTEND
                : FIELD_SOURCE_REQUIREMENT;
    }

    private Map<String, Object> entityPayload() {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("name", domain.entityName());
        entity.put("fields", domain.fields().stream()
                .map(ApiContractArtifact::fieldPayload)
                .toList());
        return Collections.unmodifiableMap(entity);
    }

    private Map<String, Object> schemaTablePayload() {
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("name", domain.tableName());
        table.put("fields", domain.fields().stream()
                .map(ApiContractArtifact::fieldPayload)
                .toList());
        return Collections.unmodifiableMap(table);
    }

    private static Map<String, Object> fieldPayload(ApiField field) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonName", field.jsonName());
        payload.put("goType", field.goType());
        payload.put("sqliteType", field.sqliteType());
        payload.put("description", field.description());
        return Collections.unmodifiableMap(payload);
    }

    private static Map<String, Object> endpointPayload(ApiEndpoint endpoint) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("method", endpoint.method());
        payload.put("path", endpoint.path());
        payload.put("action", endpoint.action());
        return Collections.unmodifiableMap(payload);
    }

    private static List<ApiEndpoint> standardEndpoints(String moduleName) {
        String resourcePath = API_PREFIX + "/" + moduleName;
        return List.of(
                new ApiEndpoint("POST", resourcePath, "create"),
                new ApiEndpoint("PUT", resourcePath + "/{id}", "update"),
                new ApiEndpoint("GET", resourcePath + "/{id}", "detail"),
                new ApiEndpoint("POST", resourcePath + "/list/page", "page"),
                new ApiEndpoint("DELETE", resourcePath + "/{id}", "delete")
        );
    }

    private static List<ApiEndpoint> requireEndpoints(Object value) {
        List<Map<String, Object>> endpointPayloads = requireMapList(
                value, "contract.endpoints", false);
        return endpointPayloads.stream()
                .map(endpoint -> new ApiEndpoint(
                        requireText(endpoint.get("method"), "contract.endpoints.method"),
                        requireText(endpoint.get("path"), "contract.endpoints.path"),
                        requireText(endpoint.get("action"), "contract.endpoints.action")
                ))
                .toList();
    }

    private static List<ApiField> requireFields(Object value, String fieldName) {
        List<Map<String, Object>> fieldPayloads = requireMapList(value, fieldName, false);
        return fieldPayloads.stream()
                .map(field -> new ApiField(
                        requireText(field.get("jsonName"), fieldName + ".jsonName"),
                        requireText(field.get("goType"), fieldName + ".goType"),
                        requireText(field.get("sqliteType"), fieldName + ".sqliteType"),
                        requireText(field.get("description"), fieldName + ".description")
                ))
                .toList();
    }

    private static Map<String, Object> requireSingleMap(Object value, String fieldName) {
        List<Map<String, Object>> values = requireMapList(value, fieldName, false);
        if (values.size() != 1) {
            throw invalidField(fieldName, "必须且只能包含一个元素");
        }
        return values.getFirst();
    }

    private static List<Map<String, Object>> requireMapList(
            Object value,
            String fieldName,
            boolean allowEmpty
    ) {
        if (!(value instanceof List<?> values)) {
            throw invalidField(fieldName, "必须为对象列表");
        }
        if (!allowEmpty && values.isEmpty()) {
            throw invalidField(fieldName, "不能为空");
        }
        List<Map<String, Object>> result = new ArrayList<>(values.size());
        for (Object item : values) {
            result.add(requireMap(item, fieldName));
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> requireMap(Object value, String fieldName) {
        if (!(value instanceof Map<?, ?> values)) {
            throw invalidField(fieldName, "必须为对象");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw invalidField(fieldName, "键必须为字符串");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static List<String> requireStringList(
            Object value,
            String fieldName,
            boolean allowEmpty
    ) {
        if (!(value instanceof List<?> values)) {
            throw invalidField(fieldName, "必须为字符串列表");
        }
        List<String> result = values.stream()
                .map(item -> requireText(item, fieldName))
                .toList();
        if (!allowEmpty && result.isEmpty()) {
            throw invalidField(fieldName, "不能为空");
        }
        return result;
    }

    private static String requireExactText(
            Map<String, Object> payload,
            String fieldName,
            String expected
    ) {
        String actual = requireText(payload.get(fieldName), "contract." + fieldName);
        if (!expected.equals(actual)) {
            throw invalidField("contract." + fieldName, "不受支持: " + actual);
        }
        return actual;
    }

    private static String requireSource(String value) {
        if (!SOURCE_PLANNER.equals(value) && !SOURCE_FRONTEND_FIRST_UPGRADE.equals(value)) {
            throw invalidField("source", "不受支持: " + value);
        }
        return value;
    }

    private static String requireIdentifier(Object value, String fieldName, Pattern pattern) {
        String text = requireText(value, fieldName);
        if (!pattern.matcher(text).matches()) {
            throw invalidField(fieldName, "格式不合法: " + text);
        }
        return text;
    }

    private static String requireText(Object value, String fieldName) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalidField(fieldName, "必须为非空字符串");
        }
        return text.trim();
    }

    private static IllegalArgumentException invalidField(String fieldName, String reason) {
        return new IllegalArgumentException("API 字段契约制品字段 " + fieldName + ": " + reason);
    }

    /** Planner 推断出的最小业务领域。 */
    public record ApiDomain(
            String moduleName,
            String entityName,
            String tableName,
            List<ApiField> fields
    ) {

        public ApiDomain {
            moduleName = requireIdentifier(moduleName, "domain.moduleName", MODULE_NAME);
            entityName = requireIdentifier(entityName, "domain.entityName", ENTITY_NAME);
            tableName = requireIdentifier(tableName, "domain.tableName", TABLE_NAME);
            fields = fields == null ? List.of() : List.copyOf(fields);
            if (fields.isEmpty()) {
                throw invalidField("domain.fields", "不能为空");
            }
            Set<String> fieldNames = new LinkedHashSet<>();
            for (ApiField field : fields) {
                if (field == null) {
                    throw invalidField("domain.fields", "不能包含空字段");
                }
                if (!fieldNames.add(field.jsonName())) {
                    throw invalidField("domain.fields", "字段名不能重复: " + field.jsonName());
                }
            }
        }
    }

    /** 跨 JSON、Go 与 SQLite 的字段映射。 */
    public record ApiField(
            String jsonName,
            String goType,
            String sqliteType,
            String description
    ) {

        public ApiField {
            jsonName = requireIdentifier(jsonName, "field.jsonName", FIELD_NAME);
            goType = requireText(goType, "field.goType");
            sqliteType = requireText(sqliteType, "field.sqliteType");
            description = requireText(description, "field.description");
        }
    }

    private record ApiEndpoint(String method, String path, String action) {

        private ApiEndpoint {
            method = requireText(method, "endpoint.method");
            path = requireText(path, "endpoint.path");
            action = requireText(action, "endpoint.action");
        }
    }
}
