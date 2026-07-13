package com.rush.rushaicodemother.common.query;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SortFieldWhitelistTest {

    private final SortFieldWhitelist whitelist = SortFieldWhitelist.of(
            "createTime",
            Map.of("createTime", "create_time", "name", "display_name")
    );

    @Test
    void nullAndBlankFieldsMustUseDefaultColumn() {
        assertEquals("create_time", whitelist.resolve(null));
        assertEquals("create_time", whitelist.resolve(" "));
    }

    @Test
    void unknownFieldMustNotReachDatabaseOrderBy() {
        assertEquals("create_time", whitelist.resolve("id desc; drop table user"));
    }

    @Test
    void allowedApiFieldMustResolveToMappedColumn() {
        assertEquals("display_name", whitelist.resolve("name"));
    }
}
