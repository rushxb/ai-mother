package com.rush.rushaicodemother.orchestration.template;

import org.springframework.stereotype.Component;

import java.util.Set;

/** 与应用程序一起打包的项目模板的规范白名单。 */
@Component
public class ProjectTemplateCatalog {

    public static final String VUE_BASIC = "vue-web-basic";
    public static final String VUE_ADMIN = "vue-web-admin";
    public static final String VUE_MOBILE = "vue-web-mobile";
    public static final String VUE_LANDING = "vue-web-landing";
    public static final String GO_SQLITE_BACKEND = "go-sqlite-backend-basic";

    private static final Set<String> KNOWN_TEMPLATE_IDS = Set.of(
            VUE_BASIC,
            VUE_ADMIN,
            VUE_MOBILE,
            VUE_LANDING,
            GO_SQLITE_BACKEND
    );
    private static final Set<String> NODE_TEMPLATE_IDS = Set.of(
            VUE_BASIC,
            VUE_ADMIN,
            VUE_MOBILE,
            VUE_LANDING
    );

    public boolean isKnown(String templateId) {
        return templateId != null && KNOWN_TEMPLATE_IDS.contains(templateId);
    }

    public boolean isNodeTemplate(String templateId) {
        return isNodeTemplateId(templateId);
    }

    /**
 * 校验并返回有效的{@code Known}。
 *
 * @param templateId 模板编号
 * @return 处理后的{@code Known}文本
 */
    public String requireKnown(String templateId) {
        if (!isKnown(templateId)) {
            throw new IllegalArgumentException("Unknown project template id");
        }
        return templateId;
    }

    /**
 * 校验并返回有效的节点模板。
 *
 * @param templateId 模板编号
 * @return 处理后的节点模板文本
 */
    public String requireNodeTemplate(String templateId) {
        requireKnown(templateId);
        if (!isNodeTemplate(templateId)) {
            throw new IllegalArgumentException("Project template does not contain Node.js dependencies");
        }
        return templateId;
    }

    public static boolean isNodeTemplateId(String templateId) {
        return templateId != null && NODE_TEMPLATE_IDS.contains(templateId);
    }
}