package com.yupi.yuaicodemother.orchestration.recipe;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationRecipeLibraryTest {

    private final GenerationRecipeLibrary library = new GenerationRecipeLibrary();

    @Test
    void shouldMatchAuthAndCrudRecipesWithinBudget() {
        List<GenerationRecipe> recipes = library.match("新增登录权限，并补一个用户管理 CRUD 列表搜索分页", "");

        assertTrue(recipes.stream().anyMatch(recipe -> "auth-basic".equals(recipe.id())));
        assertTrue(recipes.stream().anyMatch(recipe -> "crud-list-search".equals(recipe.id())));
        assertTrue(recipes.size() <= 3);
    }

    @Test
    void shouldExposeDatabaseRecipeHints() {
        List<GenerationRecipe> recipes = library.match("接入 sqlite database 后端 API", "");

        assertEquals("database-service", recipes.get(0).id());
        assertTrue(library.modules(recipes).contains("database"));
        assertTrue(library.contextFileHints(recipes).contains("backend"));
        assertTrue(recipes.get(0).databaseRequired());
    }
}
