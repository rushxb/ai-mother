package com.rush.rushaicodemother.orchestration.create.recipe;

import org.springframework.stereotype.Component;

import static com.rush.rushaicodemother.orchestration.create.recipe.RecipeSpecSupport.densityTokens;
import static com.rush.rushaicodemother.orchestration.create.recipe.RecipeValueSupport.escape;

/** 从规范化的前端选项呈现管理主题令牌。 */
@Component
final class AdminThemeTemplate {

    /** 返回{@code theme}{@code Css}。 */
    String themeCss(AdminRecipe recipe) {
        DensityTokens density = densityTokens(recipe.frontend().density());
        String styleComment = String.join(", ", recipe.frontend().styleKeywords());
        return """
                /* 样式关键词: %s */
                :root {
                  --color-primary: %s;
                  --color-success: #10b981;
                  --color-warning: #f59e0b;
                  --color-danger: #ef4444;
                  --color-accent: %s;
                  --panel-bg: #ffffff;
                  --panel-radius: %s;
                  --dashboard-padding: %s;
                  --dashboard-gap: %s;
                  --table-row-height: %s;
                  --table-font-size: %s;
                  --chip-padding: %s;
                  --surface-muted: %s;
                }
                .style-ops {
                  --surface-muted: #f8fafc;
                }
                .style-premium {
                  --surface-muted: #f5f3ff;
                }
                .style-medical-trust {
                  --surface-muted: #ecfeff;
                }
                .style-education-warm {
                  --surface-muted: #fff7ed;
                }
                """.formatted(
                escape(styleComment),
                recipe.primary(),
                recipe.accent(),
                recipe.frontend().radius(),
                density.padding(),
                density.gap(),
                density.rowHeight(),
                density.fontSize(),
                density.chipPadding(),
                recipe.frontend().surfaceMuted()
        );
    }
}
