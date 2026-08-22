package com.rush.rushaicodemother.orchestration.artifact;

import com.rush.rushaicodemother.orchestration.artifact.ApiContractArtifact.ApiDomain;
import com.rush.rushaicodemother.orchestration.artifact.ApiContractArtifact.ApiField;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiContractArtifactTest {

    @Test
    void shouldRoundTripCanonicalFrontendFirstContract() {
        ApiContractArtifact original = contract(true);

        GenerationArtifact persisted = original.toArtifact();
        ApiContractArtifact restored = ApiContractArtifact.fromArtifact(persisted);

        assertThat(persisted.key()).isEqualTo(ApiContractArtifact.KEY);
        assertThat(restored.source()).isEqualTo("frontend_first_upgrade");
        assertThat(restored.userMessage()).isEqualTo("增加商品管理");
        assertThat(restored.domain()).isEqualTo(original.domain());
        assertThat(restored.contractPayload()).isEqualTo(original.contractPayload());
    }

    @Test
    void endpointPathMustMatchThePersistedModule() {
        GenerationArtifact canonical = contract(false).toArtifact();
        Map<String, Object> forgedPayload = new LinkedHashMap<>(canonical.payload());
        Map<String, Object> forgedContract = mutableMap(forgedPayload.get("contract"));
        List<Map<String, Object>> forgedEndpoints = mutableMapList(forgedContract.get("endpoints"));
        Map<String, Object> firstEndpoint = new LinkedHashMap<>(forgedEndpoints.getFirst());
        firstEndpoint.put("path", "/api/order");
        forgedEndpoints.set(0, firstEndpoint);
        forgedContract.put("endpoints", forgedEndpoints);
        forgedPayload.put("contract", forgedContract);
        GenerationArtifact forged = GenerationArtifact.of(
                ApiContractArtifact.KEY,
                "Planner",
                "伪造 API 字段契约",
                forgedPayload
        );

        assertThatThrownBy(() -> ApiContractArtifact.fromArtifact(forged))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contract.endpoints");
    }

    private ApiContractArtifact contract(boolean frontendFirstUpgrade) {
        return ApiContractArtifact.create(
                frontendFirstUpgrade,
                "增加商品管理",
                new ApiDomain(
                        "product",
                        "Product",
                        "products",
                        List.of(
                                new ApiField("id", "int64", "integer", "主键"),
                                new ApiField("name", "string", "text", "名称")
                        )
                )
        );
    }

    private Map<String, Object> mutableMap(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        Map<?, ?> values = (Map<?, ?>) value;
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private List<Map<String, Object>> mutableMapList(Object value) {
        assertThat(value).isInstanceOf(List.class);
        List<?> values = (List<?>) value;
        List<Map<String, Object>> result = new ArrayList<>();
        values.forEach(item -> result.add(mutableMap(item)));
        return result;
    }
}
