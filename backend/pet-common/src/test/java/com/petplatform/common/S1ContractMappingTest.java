package com.petplatform.common;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Static affected-contract checks; neither live HTTP nor database idempotency tests. */
class S1ContractMappingTest {
    private static final Set<String> LEGACY_OPERATIONS = Set.of(
            "createOrder", "listMyOrders", "getMyOrder", "createOrderPayment", "rescheduleOrder",
            "applyRefund", "getReviewEligibility", "createReview",
            "merchantConfirmOrder", "merchantRejectOrder", "merchantApproveRefund", "merchantRejectRefund",
            "verifyPlatformOrder", "abnormalCloseOrder");
    private static final Set<String> LEGACY_CREATES = Set.of(
            "createOrder", "applyRefund", "createReview");
    private static final Set<String> LEGACY_SCHEMAS = Set.of(
            "DisplayOrderStatus", "FulfillmentType", "PublicId", "DecimalAmount", "DecimalAmountOutput",
            "CreateOrderRequest", "CreateOrderData", "RescheduleRequest", "RefundApplyRequest",
            "RefundApplicationData", "CreateAftersaleRequest", "ReviewEligibilityData", "CreateReviewRequest",
            "MerchantRejectOrderRequest", "MerchantRejectRefundRequest", "AftersaleDecisionRequest",
            "OrderDetailData", "OrderActions", "BaseEnvelope", "ErrorEnvelope", "CreateOrderResponseEnvelope",
            "RefundApplicationResponseEnvelope", "ReviewEligibilityResponseEnvelope", "OrderDetailResponseEnvelope");
    private static Map<String, Object> api;
    private static Path root;

    @BeforeAll
    static void readActualRepositoryContract() throws IOException {
        root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("docs/04-api/11-OpenAPI-Core-v0.4.yaml"))) {
            root = root.getParent();
        }
        assertNotNull(root, "Run inside the repository; do not use a copied fixture contract");
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (Reader reader = Files.newBufferedReader(root.resolve("docs/04-api/11-OpenAPI-Core-v0.4.yaml"), StandardCharsets.UTF_8)) {
            api = map(new Yaml(new SafeConstructor(options)).load(reader));
        }
    }

    @Test
    void allExistingWritesReferenceStrictUuidAndKeepPublicReplayResponses() {
        Map<String, Object> header = at(api, "components", "parameters", "RequestId");
        assertEquals("X-Request-Id", header.get("name"));
        assertEquals("header", header.get("in"));
        assertEquals(true, header.get("required"));
        Map<String, Object> schema = at(header, "schema");
        assertEquals("string", schema.get("type"));
        assertEquals("uuid", schema.get("format"));
        assertEquals(36, schema.get("maxLength"));
        for (String value : List.of("11111111-1111-4111-8111-111111111111", "ABCDEFAB-1234-1234-ABCD-1234567890AB")) {
            assertTrue(matchesSchemaPattern(schema, value));
            assertEquals(value, PublicContractChecks.requireTerminalRequestId(value));
        }
        for (String value : List.of("1-1-1-1-1", "TASK:X:1:0", "11111111-1111-4111-8111-111111111111\n")) {
            assertFalse(matchesSchemaPattern(schema, value));
        }
        int writes = 0;
        int operations = 0;
        int creates = 0;
        Set<String> seen = new HashSet<>();
        Set<String> seenCreates = new HashSet<>();
        for (Object path : at(api, "paths").values()) {
            for (var method : map(path).entrySet()) {
                if (!Set.of("get", "post", "put", "patch", "delete").contains(method.getKey())) continue;
                Map<String, Object> operation = map(method.getValue());
                String operationId = (String) operation.get("operationId");
                if (!LEGACY_OPERATIONS.contains(operationId)) continue;
                assertTrue(seen.add(operationId), "Duplicate legacy operation: " + operationId);
                operations++;
                assertEquals(List.of(Map.of("bearerAuth", List.of())), operation.get("security"));
                List<?> parameters = (List<?>) operation.getOrDefault("parameters", List.of());
                boolean hasRequestId = parameters.stream().anyMatch(p -> "#/components/parameters/RequestId".equals(map(p).get("$ref")));
                if (method.getKey().equals("get")) {
                    assertFalse(hasRequestId);
                } else {
                    writes++;
                    assertTrue(hasRequestId, operation.get("operationId").toString());
                    Map<String, Object> responses = at(operation, "responses");
                    assertTrue(responses.keySet().containsAll(Set.of("200", "401", "403", "409", "503")));
                    assertFalse(responses.containsKey("202"), "Do not turn synchronous success into generic acceptance");
                    if (responses.containsKey("201")) {
                        creates++;
                        seenCreates.add(operationId);
                        Object created = resolve(map(responses.get("201"))).get("content");
                        Object replayed = resolve(map(responses.get("200"))).get("content");
                        assertEquals(created, replayed);
                        if (Set.of("createOrder", "applyRefund").contains(operationId)) {
                            assertNotNull(created, "Keep the existing concrete response schemas");
                        }
                    }
                }
            }
        }
        assertEquals(LEGACY_OPERATIONS, seen, "AUTH additions must preserve every S1 business operation");
        assertEquals(LEGACY_CREATES, seenCreates);
        assertEquals(14, operations, "Two former AFS drafts are pinned separately by Contract51");
        assertEquals(11, writes);
        assertEquals(3, creates);
    }

    @Test
    void snowflakeSchemaIsUsedByActualIdsAndKeepsNullableFields() {
        PublicIdCodec codec = new DecimalPublicIdCodec();
        Map<String, Object> id = at(api, "components", "schemas", "PublicId");
        checkIdShape(id);
        for (String value : List.of("1", "9007199254740993", "9223372036854775807")) {
            assertTrue(matchesSchemaPattern(id, value));
            assertEquals(value, codec.toApi(codec.fromApi(value)));
        }
        for (String value : List.of("0", "01", "-1", "+1", "1e3", "１", "١", "1\n", "99999999999999999999")) {
            assertFalse(matchesSchemaPattern(id, value), value);
        }
        // JSON Schema string pattern cannot itself enforce the numeric Long upper bound.
        assertTrue(matchesSchemaPattern(id, "9223372036854775808"));
        assertThrows(IllegalArgumentException.class, () -> codec.fromApi("9223372036854775808"));
        for (String parameter : List.of("OrderId", "ApplicationId", "AfterSaleId")) {
            checkIdShape(resolve(at(api, "components", "parameters", parameter, "schema")));
        }
        int usages = 0;
        for (Object schema : legacySchemas()) {
            for (var property : map(map(schema).getOrDefault("properties", Map.of())).entrySet()) {
                String name = property.getKey();
                if ((name.endsWith("Id") && !name.equals("traceId")) || name.equals("orderNo")) {
                    checkIdShape(resolve(map(property.getValue())));
                    usages++;
                } else if (name.endsWith("FileIds")) {
                    checkIdShape(resolve(at(property.getValue(), "items")));
                    usages++;
                }
            }
        }
        assertTrue(usages >= 10, "An unused PublicId declaration is insufficient");
        assertEquals(true, at(api, "components", "schemas", "CreateOrderRequest", "properties", "couponInstanceId").get("nullable"));
        assertEquals(true, at(api, "components", "schemas", "RefundApplicationData", "properties", "refundOrderId").get("nullable"));
        assertFalse(at(api, "components", "schemas", "BaseEnvelope", "properties", "traceId").containsKey("$ref"));
    }

    @Test
    void amountInputsAndOutputsUseDistinctActualSchemasWithoutOpeningNegativeBusinessAmounts() {
        Map<String, Object> input = at(api, "components", "schemas", "DecimalAmount");
        Map<String, Object> output = at(api, "components", "schemas", "DecimalAmountOutput");
        assertEquals("string", input.get("type"));
        assertEquals("string", output.get("type"));
        MoneyCodec codec = new FixedDecimalMoneyCodec();
        for (String value : List.of("0", "128", "128.0", "128.00", "9999999999999999.99")) {
            assertTrue(matchesSchemaPattern(input, value));
            assertTrue(matchesSchemaPattern(output, codec.format(codec.parse(value))));
        }
        for (String value : List.of("128.", "128.000", "128.001", "1e2", "01", "-0.00", "-1.00",
                "10000000000000000.00", "128.00\n")) {
            assertFalse(matchesSchemaPattern(input, value), value);
        }
        for (String value : List.of("128", "128.0", "128.000", "128.00\n")) {
            assertFalse(matchesSchemaPattern(output, value), value);
        }
        assertEquals("-1.00", codec.format(codec.parse("-1")), "Public numeric support is not business eligibility");
        for (String data : List.of("CreateOrderData", "OrderDetailData")) {
            assertEquals(output, resolve(at(api, "components", "schemas", data, "properties", "payAmount")));
        }
        // Contract51 retains the old schema names as references, while the actual HTTP DTOs use
        // stricter two-place nullable decimal text. This does not change the common money codec.
        Map<String, Object> create = resolve(at(api, "components", "schemas", "CreateAftersaleRequest"));
        Map<String, Object> decision = resolve(at(api, "components", "schemas", "AftersaleDecisionRequest"));
        assertEquals(at(api, "components", "schemas", "AfterSaleCreateRequest"), create);
        assertEquals(at(api, "components", "schemas", "AfterSaleDecisionRequest"), decision);
        Map<String, Object> requestedAmount = at(create, "properties", "requestedAmount");
        Map<String, Object> refundAmount = at(decision, "properties", "refundAmount");
        assertEquals(requestedAmount, refundAmount);
        for (Map<String, Object> nullableAmount : List.of(requestedAmount, refundAmount)) {
            assertEquals("string", nullableAmount.get("type"));
            assertEquals(true, nullableAmount.get("nullable"));
            assertFalse(nullableAmount.containsKey("allOf"), "OAS3 nullable must apply to the same typed schema");
            for (String value : List.of("0.00", "128.00", "9999999999999999.99")) {
                assertTrue(matchesSchemaPattern(nullableAmount, value), value);
            }
            for (String value : List.of("0", "128", "128.0", "128.000", "128.001", "1e2", "01.00",
                    "-0.00", "-1.00", "10000000000000000.00", "128.00\n")) {
                assertFalse(matchesSchemaPattern(nullableAmount, value), value);
            }
        }
    }

    @Test
    void contract51PinsRealAftersaleRoutesStrictBodiesAndClosedPublicFunding() {
        Map<String, Object> create = at(api, "paths", "/c/orders/{orderId}/aftersales", "post");
        Map<String, Object> decision = at(api, "paths", "/admin/aftersales/{afterSaleId}/decisions", "post");
        assertFalse(at(api, "paths").containsKey("/admin/aftersales/{afterSaleId}/decision"),
                "The former unimplemented singular draft is not a compatibility route");
        assertEquals("createAftersale", create.get("operationId"));
        assertEquals("decideAftersale", decision.get("operationId"));
        for (Map<String, Object> operation : List.of(create, decision)) {
            assertEquals(List.of(Map.of("bearerAuth", List.of())), operation.get("security"));
            assertEquals(false, operation.get("x-default-enabled"));
            assertEquals("IMPLEMENTED_DEFAULT_OFF", operation.get("x-implementation-status"));
            assertEquals("51-AfterSale-Http-Contract-v0.1.md", operation.get("x-contract"));
            assertTrue(((List<?>) operation.get("parameters")).contains(Map.of("$ref", "#/components/parameters/RequestId")));
            assertTrue(at(operation, "responses").keySet().containsAll(Set.of("200", "400", "401", "403", "409", "503")));
            assertFalse(at(operation, "responses").containsKey("202"));
            assertEquals(true, at(operation, "requestBody").get("required"));
            assertEquals(false, resolve(at(operation, "requestBody", "content", "application/json", "schema")).get("additionalProperties"));
        }
        assertEquals("USER", create.get("x-route-party"));
        assertEquals("MINIAPP", create.get("x-audience"));
        assertEquals(at(create, "responses", "200", "content"), at(create, "responses", "201", "content"));
        assertEquals("OPS", decision.get("x-route-party"));
        assertEquals("ADMIN_WEB", decision.get("x-audience"));
        assertEquals(List.of("aftersale.decide"), decision.get("x-required-actions"));
        assertEquals(false, decision.get("x-public-refund-enabled"));
        assertEquals(List.of("REJECT", "RESERVICE", "OTHER"), decision.get("x-executable-decision-types"));
        assertFalse(at(decision, "responses").containsKey("201"));
        Map<String, Object> createBody = resolve(at(create, "requestBody", "content", "application/json", "schema"));
        assertEquals(Set.of("typeCode", "demandCode", "description", "evidenceAssetIds"), new HashSet<>((List<?>) createBody.get("required")));
        assertEquals(Set.of("typeCode", "demandCode", "description", "evidenceAssetIds", "requestedAmount", "newProblemStatement"), at(createBody, "properties").keySet());
        Map<String, Object> evidence = at(createBody, "properties", "evidenceAssetIds");
        assertEquals("array", evidence.get("type"));
        assertEquals(6, evidence.get("maxItems"));
        assertEquals(true, evidence.get("uniqueItems"));
        assertFalse(evidence.containsKey("nullable"));
        checkIdShape(resolve(at(evidence, "items")));
        Map<String, Object> decisionBody = resolve(at(decision, "requestBody", "content", "application/json", "schema"));
        assertEquals(Set.of("expectedVersion", "decisionType", "reason"), new HashSet<>((List<?>) decisionBody.get("required")));
        assertEquals(Set.of("expectedVersion", "decisionType", "reason", "refundAmount"), at(decisionBody, "properties").keySet());
        assertEquals(Set.of("REJECT", "RESERVICE", "OTHER", "FULL_REFUND", "PARTIAL_REFUND"),
                new HashSet<>((List<?>) at(decisionBody, "properties", "decisionType").get("enum")));
    }

    @Test
    void timeAndReferenceMappingsAreCompleteAndAuthorityFilesUseSuccessWording() throws IOException {
        int timeFields = 0;
        for (Object schema : legacySchemas()) {
            for (Object field : map(map(schema).getOrDefault("properties", Map.of())).values()) {
                if ("date-time".equals(map(field).get("format"))) {
                    timeFields++;
                    assertEquals("string", map(field).get("type"));
                    assertEquals("milliseconds", map(field).get("x-precision"));
                }
            }
        }
        assertTrue(timeFields >= 10);
        validateReferences(api);
        String http = Files.readString(root.resolve("docs/04-api/10-HTTP-API-Contract-v0.4.md"));
        assertFalse(http.contains("→ 返回第一次处理结果"));
        assertTrue(http.contains("→ 当前权限校验通过后返回第一次成功业务回执"));
        assertTrue(http.contains("wechatPayParameters"));
        assertTrue(http.contains("23-公共接口与幂等契约补充-v0.1.md"));
        String internal = Files.readString(root.resolve("docs/04-api/07-内部API-Contract-v0.6.md"));
        assertTrue(internal.contains("失败整笔回滚业务但保留绑定"));
        assertTrue(internal.contains("23-公共接口与幂等契约补充-v0.1.md"));
    }

    private static void checkIdShape(Map<String, Object> schema) {
        assertEquals("string", schema.get("type"));
        assertEquals(19, schema.get("maxLength"));
        assertEquals("9223372036854775807", schema.get("x-maximum-decimal"));
        assertFalse(matchesSchemaPattern(schema, "01"));
    }

    private static List<Map<String, Object>> legacySchemas() {
        Map<String, Object> schemas = at(api, "components", "schemas");
        assertTrue(schemas.keySet().containsAll(LEGACY_SCHEMAS), "Keep every pre-AUTH S1 schema");
        // AUTH contains UUID requestId and opaque credentials, not only Snowflake IDs.
        // Its actual schemas and examples are independently checked by e2e/test_auth_contract.py.
        return LEGACY_SCHEMAS.stream().map(name -> map(schemas.get(name))).toList();
    }

    private static boolean matchesSchemaPattern(Map<String, Object> schema, String value) {
        // JSON Schema pattern uses a search, not Java matches(): expose missing end anchors.
        return Pattern.compile((String) schema.get("pattern")).matcher(value).find();
    }

    private static Map<String, Object> resolve(Map<String, Object> schema) {
        if (schema.containsKey("$ref")) return pointer((String) schema.get("$ref"));
        if (schema.containsKey("allOf")) return resolve(map(((List<?>) schema.get("allOf")).getFirst()));
        return schema;
    }

    private static void validateReferences(Object value) {
        if (value instanceof Map<?, ?> values) {
            if (values.containsKey("$ref")) assertNotNull(pointer((String) values.get("$ref")));
            values.values().forEach(S1ContractMappingTest::validateReferences);
        } else if (value instanceof List<?> values) {
            values.forEach(S1ContractMappingTest::validateReferences);
        }
    }

    private static Map<String, Object> pointer(String reference) {
        assertTrue(reference.startsWith("#/"));
        return at(api, reference.substring(2).split("/"));
    }

    private static Map<String, Object> at(Object value, String... keys) {
        for (String key : keys) value = map(value).get(key);
        assertNotNull(value, String.join("/", keys));
        return map(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
