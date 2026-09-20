package com.petplatform.merchant.biz.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class TencentMapValidationProviderTest {
  private HttpServer server;
  private AtomicInteger calls;
  private volatile String forwardResponse;
  private volatile String reverseResponse;
  private volatile int statusCode;
  private volatile long bodyDelayMillis;
  private volatile List<Map<String, String>> queries;

  @BeforeEach
  void start() throws Exception {
    calls = new AtomicInteger();
    queries = Collections.synchronizedList(new ArrayList<>());
    forwardResponse = forward(30.6570, 104.0650, "四川省", "成都市", "510104", 9, 10, 30);
    reverseResponse = reverse("中国", "四川省", "成都市", "156", "510104", "156510100");
    statusCode = 200;
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/ws/geocoder/v1/",
        exchange -> {
          try {
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            queries.add(query);
            calls.incrementAndGet();
            byte[] body =
                (query.containsKey("address") ? forwardResponse : reverseResponse)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, body.length);
            if (bodyDelayMillis > 0) Thread.sleep(bodyDelayMillis);
            exchange.getResponseBody().write(body);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
  }

  @Test
  void acceptsOnlyPreciseForwardAndReverseChengduFactsWithinConfiguredDistance() {
    TencentMapValidationProvider provider = provider(1_000, 2_000);
    reverseResponse =
        reverseResponse
            .replace("\"adcode\":\"510104\"", "\"adcode\":510104")
            .replace("\"nation_code\":\"156\"", "\"nation_code\":156")
            .replace("\"city_code\":\"156510100\"", "\"city_code\":156510100");
    assertTrue(
        provider.isReasonable(
            "chengdu",
            "锦江区人民东路1号",
            new BigDecimal("104.0651"),
            new BigDecimal("30.6571")));
    assertEquals(2, calls.get());
    assertEquals("锦江区人民东路1号", queries.get(0).get("address"));
    assertEquals("成都", queries.get(0).get("region"));
    assertEquals("30.6571,104.0651", queries.get(1).get("location"));
    assertEquals("qa-map-key", queries.get(0).get("key"));
    assertEquals("qa-map-key", queries.get(1).get("key"));
  }

  @Test
  void rejectsUnsupportedCityWithoutCallingProviderAndRejectsDistanceOrCityMismatch() {
    TencentMapValidationProvider provider = provider(500, 2_000);
    assertFalse(
        provider.isReasonable(
            "shanghai", "人民路1号", new BigDecimal("104.065"), new BigDecimal("30.657")));
    assertEquals(0, calls.get());

    forwardResponse = forward(30.70, 104.10, "四川省", "成都市", "510104", 9, 10, 30);
    assertFalse(
        provider.isReasonable(
            "chengdu", "人民路1号", new BigDecimal("104.065"), new BigDecimal("30.657")));
    assertEquals(1, calls.get(), "distance rejection does not need a reverse request");

    calls.set(0);
    forwardResponse = forward(30.657, 104.065, "四川省", "成都市", "510104", 9, 10, 30);
    reverseResponse = reverse("中国", "四川省", "绵阳市", "156", "510703", "156510700");
    assertFalse(
        provider.isReasonable(
            "chengdu", "人民路1号", new BigDecimal("104.065"), new BigDecimal("30.657")));
    assertEquals(2, calls.get());
  }

  @Test
  void rejectsLowQualityOrProviderReportedDeviation() {
    TencentMapValidationProvider provider = provider(1_000, 2_000);
    forwardResponse = forward(30.657, 104.065, "四川省", "成都市", "510104", 6, 9, 30);
    assertFalse(
        provider.isReasonable(
            "chengdu", "人民路1号", new BigDecimal("104.065"), new BigDecimal("30.657")));
    forwardResponse = forward(30.657, 104.065, "四川省", "成都市", "510104", 9, 10, 1_001);
    assertFalse(
        provider.isReasonable(
            "chengdu", "人民路1号", new BigDecimal("104.065"), new BigDecimal("30.657")));
  }

  @Test
  void acceptsConfirmedSixDigitLegacyCityCodeButRejectsWrongNationPrefix() {
    TencentMapValidationProvider provider = provider(1_000, 2_000);
    reverseResponse = reverse("中国", "四川省", "成都市", "156", "510104", "510100");
    assertTrue(
        provider.isReasonable(
            "chengdu", "人民路1号", new BigDecimal("104.065"), new BigDecimal("30.657")));

    reverseResponse = reverse("中国", "四川省", "成都市", "840", "510104", "510100");
    assertFalse(
        provider.isReasonable(
            "chengdu", "人民路1号", new BigDecimal("104.065"), new BigDecimal("30.657")));
    reverseResponse = reverse("中国", "四川省", "成都市", "840", "510104", "840510100");
    assertFalse(
        provider.isReasonable(
            "chengdu", "人民路1号", new BigDecimal("104.065"), new BigDecimal("30.657")));
  }

  @Test
  void quotaMalformedHttpAndTimeoutFailuresCloseWithoutLeakingKey() {
    TencentMapValidationProvider provider = provider(1_000, 250);
    forwardResponse = "{\"status\":120,\"message\":\"limit\"}";
    dependency(provider);
    forwardResponse = "{\"status\":0,\"status\":0}";
    dependency(provider);
    statusCode = 503;
    dependency(provider);
    statusCode = 200;
    forwardResponse = "{\"status\":0,\"padding\":\"" + "a".repeat(256 * 1024) + "\"}";
    dependency(provider);
    forwardResponse = forward(30.657, 104.065, "四川省", "成都市", "510104", 9, 10, 30);
    bodyDelayMillis = 800;
    long started = System.nanoTime();
    dependency(provider);
    assertTrue(
        Duration.ofNanos(System.nanoTime() - started).toMillis() < 2_000,
        "deadline must include a body that stalls after timely headers");
  }

  @Test
  void constructorRejectsNonOfficialNonLoopbackEndpointAndMissingPolicy() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new TencentMapValidationProvider("http://example.com", "key", Duration.ofSeconds(1), 1000));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TencentMapValidationProvider(
                TencentMapValidationProvider.OFFICIAL_ENDPOINT, "", Duration.ofSeconds(1), 1000));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TencentMapValidationProvider(
                TencentMapValidationProvider.OFFICIAL_ENDPOINT, "key", Duration.ofSeconds(1), 0));
  }

  private TencentMapValidationProvider provider(double meters, long timeoutMillis) {
    return new TencentMapValidationProvider(
        "http://127.0.0.1:" + server.getAddress().getPort(),
        "qa-map-key",
        Duration.ofMillis(timeoutMillis),
        meters);
  }

  private void dependency(TencentMapValidationProvider provider) {
    ApiException failure =
        assertThrows(
            ApiException.class,
            () ->
                provider.isReasonable(
                    "chengdu",
                    "人民路1号",
                    new BigDecimal("104.065"),
                    new BigDecimal("30.657")));
    assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, failure.code());
    assertFalse(failure.getMessage().contains("qa-map-key"));
    assertNull(failure.getCause());
  }

  private static Map<String, String> query(String raw) {
    Map<String, String> result = new LinkedHashMap<>();
    for (String item : raw.split("&")) {
      String[] pair = item.split("=", 2);
      result.put(
          URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
          URLDecoder.decode(pair.length == 1 ? "" : pair[1], StandardCharsets.UTF_8));
    }
    return result;
  }

  private static String forward(
      double lat,
      double lng,
      String province,
      String city,
      String adcode,
      int reliability,
      int level,
      int deviation) {
    return "{\"status\":0,\"result\":{\"location\":{\"lat\":"
        + lat
        + ",\"lng\":"
        + lng
        + "},\"address_components\":{\"province\":\""
        + province
        + "\",\"city\":\""
        + city
        + "\"},\"ad_info\":{\"adcode\":\""
        + adcode
        + "\"},\"reliability\":"
        + reliability
        + ",\"level\":"
        + level
        + ",\"deviation\":"
        + deviation
        + "}}";
  }

  private static String reverse(
      String nation,
      String province,
      String city,
      String nationCode,
      String adcode,
      String cityCode) {
    return "{\"status\":0,\"result\":{\"address_component\":{\"nation\":\""
        + nation
        + "\",\"province\":\""
        + province
        + "\",\"city\":\""
        + city
        + "\"},\"ad_info\":{\"nation_code\":\""
        + nationCode
        + "\",\"adcode\":\""
        + adcode
        + "\",\"city_code\":\""
        + cityCode
        + "\"}}}";
  }
}
