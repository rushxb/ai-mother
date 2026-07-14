package com.rush.rushaicodemother.orchestration.create.recipe;

import java.util.List;

record AdminRecipe(String brand,
                   String domain,
                   String primary,
                   String accent,
                   String entityLabel,
                   List<RecipeField> fields,
                   FrontendOptions frontend,
                   String mockDataStyle) {
    AdminRecipe {
        fields = List.copyOf(fields == null ? List.of() : fields);
    }
}

record BackendRecipe(String packageName,
                     String structName,
                     String label,
                     String tableName,
                     List<RecipeField> fields,
                     BackendOptions options,
                     List<String> indexes) {
    BackendRecipe {
        fields = List.copyOf(fields == null ? List.of() : fields);
        indexes = List.copyOf(indexes == null ? List.of() : indexes);
    }
}

record RecipeField(String name, String type, String label, boolean required) {
}

record BasicRecipe(String brand,
                   String headline,
                   String description,
                   String domain,
                   String primary,
                   String accent,
                   String entityLabel,
                   FrontendOptions frontend) {
}

record FrontendOptions(String density,
                       List<String> styleKeywords,
                       List<String> styleClasses,
                       List<String> interactions,
                       List<String> dataViz,
                       List<String> navigation,
                       String radius,
                       String surfaceMuted) {
    FrontendOptions {
        styleKeywords = List.copyOf(styleKeywords == null ? List.of() : styleKeywords);
        styleClasses = List.copyOf(styleClasses == null ? List.of() : styleClasses);
        interactions = List.copyOf(interactions == null ? List.of() : interactions);
        dataViz = List.copyOf(dataViz == null ? List.of() : dataViz);
        navigation = List.copyOf(navigation == null ? List.of() : navigation);
    }
}

record BackendOptions(boolean pagination,
                      boolean search,
                      boolean sort,
                      boolean softDelete,
                      boolean authRequired,
                      boolean importExport,
                      boolean batchActions,
                      boolean validationRequired) {
}

record DensityTokens(String padding, String gap, String rowHeight, String fontSize, String chipPadding) {
}
