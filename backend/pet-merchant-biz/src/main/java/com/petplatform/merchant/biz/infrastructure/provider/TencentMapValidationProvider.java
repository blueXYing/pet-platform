package com.petplatform.merchant.biz.infrastructure.provider;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.merchant.biz.application.ApplicationValidationPorts.MapValidationPort;
import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

/** Tencent Location Service forward/reverse geocoding validation for the approved Chengdu slice. */
public final class TencentMapValidationProvider implements MapValidationPort {
  public static final String OFFICIAL_ENDPOINT = "https://apis.map.qq.com";
  private static final int MAX_RESPONSE_BYTES = 256 * 1024;
  private static final double EARTH_RADIUS_METERS = 6_371_008.8d;
  private static final ObjectMapper JSON =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  private final URI endpoint;
  private final String key;
  private final Duration timeout;
  private final double maxDistanceMeters;
  private final HttpClient client;

  public TencentMapValidationProvider(
      String endpoint, String key, Duration timeout, double maxDistanceMeters) {
    this.endpoint = endpoint(endpoint);
    if (key == null
        || key.isBlank()
        || key.length() > 256
        || key.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Explicit Tencent Map WebService key is required");
    }
    if (timeout == null
        || timeout.toMillis() < 100
        || timeout.toMillis() > 10_000
        || !timeout.equals(Duration.ofMillis(timeout.toMillis()))) {
      throw new IllegalArgumentException("Tencent Map timeout must be 100..10000 whole milliseconds");
    }
    if (!Double.isFinite(maxDistanceMeters) || maxDistanceMeters <= 0) {
      throw new IllegalArgumentException("Explicit positive map distance policy is required");
    }
    this.key = key;
    this.timeout = timeout;
    this.maxDistanceMeters = maxDistanceMeters;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public boolean isReasonable(
      String cityCode, String address, BigDecimal longitude, BigDecimal latitude) {
    if (!"chengdu".equals(cityCode)) return false;
    if (address == null
        || address.isBlank()
        || address.length() > 255
        || longitude == null
        || latitude == null) return false;
    double lng = longitude.doubleValue(), lat = latitude.doubleValue();
    if (!Double.isFinite(lng)
        || !Double.isFinite(lat)
        || lng < -180
        || lng > 180
        || lat < -90
        || lat > 90) return false;

    JsonNode forward = request("address", address, "region", "成都");
    if (noResult(forward)) return false;
    JsonNode forwardResult = successfulResult(forward);
    if (!forwardCity(forwardResult)
        || integer(forwardResult, "reliability") < 7
        || integer(forwardResult, "level") < 9
        || finite(forwardResult, "deviation") < 0
        || finite(forwardResult, "deviation") > maxDistanceMeters) return false;
    double mappedLng = finite(forwardResult.path("location"), "lng");
    double mappedLat = finite(forwardResult.path("location"), "lat");
    if (meters(lat, lng, mappedLat, mappedLng) > maxDistanceMeters) return false;

    JsonNode reverse = request("location", decimal(latitude) + "," + decimal(longitude), "get_poi", "0");
    if (noResult(reverse)) return false;
    return reverseCity(successfulResult(reverse));
  }

  private JsonNode request(String firstName, String firstValue, String secondName, String secondValue) {
    String query =
        encoded(firstName)
            + "="
            + encoded(firstValue)
            + "&"
            + encoded(secondName)
            + "="
            + encoded(secondValue)
            + "&output=json&key="
            + encoded(key);
    URI uri;
    try {
      uri = endpoint.resolve("/ws/geocoder/v1/?" + query);
    } catch (RuntimeException invalidUri) {
      throw unavailable();
    }
    try {
      AtomicReference<BoundedBodySubscriber> subscriber = new AtomicReference<>();
      CompletableFuture<HttpResponse<byte[]>> pending =
          client.sendAsync(
              HttpRequest.newBuilder(uri)
                  .timeout(timeout)
                  .header("Accept", "application/json")
                  .GET()
                  .build(),
              ignored -> {
                BoundedBodySubscriber bounded = new BoundedBodySubscriber();
                subscriber.set(bounded);
                return bounded;
              });
      HttpResponse<byte[]> response;
      try {
        response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException timeoutFailure) {
        BoundedBodySubscriber bounded = subscriber.get();
        if (bounded != null) bounded.cancel();
        pending.cancel(true);
        throw unavailable();
      }
      if (response.statusCode() != 200) {
        throw unavailable();
      }
      String contentType =
          response.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT);
      if (!contentType.startsWith("application/json")) {
        throw unavailable();
      }
      return JSON.readTree(response.body());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (ExecutionException | IOException | RuntimeException failure) {
      if (failure instanceof ApiException known) throw known;
      throw unavailable();
    }
  }

  private static JsonNode successfulResult(JsonNode root) {
    if (status(root) != 0) {
      throw unavailable();
    }
    JsonNode result = root.get("result");
    if (result == null || !result.isObject()) throw unavailable();
    return result;
  }

  private static boolean noResult(JsonNode root) {
    return status(root) == 347;
  }

  private static int status(JsonNode root) {
    if (root == null || !root.isObject()) throw unavailable();
    JsonNode value = root.get("status");
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw unavailable();
    return value.intValue();
  }

  private static boolean forwardCity(JsonNode result) {
    JsonNode parts = result.path("address_components");
    String adcode = code(result.path("ad_info"), "adcode");
    return "四川省".equals(text(parts, "province"))
        && "成都市".equals(text(parts, "city"))
        && adcode != null
        && adcode.matches("5101[0-9]{2}");
  }

  private static boolean reverseCity(JsonNode result) {
    JsonNode parts = result.path("address_component");
    JsonNode ad = result.path("ad_info");
    String adcode = code(ad, "adcode"), cityCode = code(ad, "city_code");
    return "中国".equals(text(parts, "nation"))
        && "四川省".equals(text(parts, "province"))
        && "成都市".equals(text(parts, "city"))
        && adcode != null
        && adcode.matches("5101[0-9]{2}")
        && "510100".equals(cityCode);
  }

  private static int integer(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw unavailable();
    return value.intValue();
  }

  private static double finite(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isNumber()) throw unavailable();
    double result = value.doubleValue();
    if (!Double.isFinite(result)) throw unavailable();
    return result;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isTextual() && !value.textValue().isBlank()
        ? value.textValue()
        : null;
  }

  private static String code(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null) return null;
    if (value.isTextual() && value.textValue().matches("[0-9]{6}")) return value.textValue();
    if (value.isIntegralNumber() && value.canConvertToInt()) {
      int number = value.intValue();
      return number >= 100000 && number <= 999999 ? Integer.toString(number) : null;
    }
    return null;
  }

  private static URI endpoint(String value) {
    try {
      URI uri = URI.create(Objects.requireNonNull(value)).normalize();
      boolean official =
          "https".equals(uri.getScheme())
              && "apis.map.qq.com".equals(uri.getHost())
              && (uri.getPort() == -1 || uri.getPort() == 443);
      boolean loopbackTest =
          "http".equals(uri.getScheme())
              && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()))
              && uri.getPort() > 0;
      if ((!official && !loopbackTest)
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null
          || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
        throw new IllegalArgumentException("Tencent Map endpoint must be official HTTPS or loopback test HTTP");
      }
      return URI.create(uri.getScheme() + "://" + uri.getAuthority());
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid Tencent Map endpoint");
    }
  }

  private static String encoded(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String decimal(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }

  private static double meters(double lat1, double lng1, double lat2, double lng2) {
    double latDelta = Math.toRadians(lat2 - lat1), lngDelta = Math.toRadians(lng2 - lng1);
    double a =
        Math.sin(latDelta / 2) * Math.sin(latDelta / 2)
            + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.sin(lngDelta / 2)
                * Math.sin(lngDelta / 2);
    return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(a)));
  }

  private static ApiException unavailable() {
    return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, "地图核验服务暂时不可用");
  }

  /** Collects at most 256 KiB and lets cancellation propagate to the HTTP subscription. */
  private static final class BoundedBodySubscriber
      implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private volatile Flow.Subscription subscription;
    private int size;

    @Override
    public CompletionStage<byte[]> getBody() {
      return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription value) {
      if (subscription != null || body.isDone()) {
        value.cancel();
        return;
      }
      subscription = value;
      value.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
      if (body.isDone()) return;
      try {
        for (ByteBuffer item : items) {
          int length = item.remaining();
          if (length > MAX_RESPONSE_BYTES - size) {
            subscription.cancel();
            body.completeExceptionally(unavailable());
            return;
          }
          byte[] chunk = new byte[length];
          item.get(chunk);
          bytes.write(chunk, 0, chunk.length);
          size += length;
        }
        subscription.request(1);
      } catch (RuntimeException failure) {
        subscription.cancel();
        body.completeExceptionally(unavailable());
      }
    }

    @Override
    public void onError(Throwable failure) {
      body.completeExceptionally(unavailable());
    }

    @Override
    public void onComplete() {
      if (size == 0) body.completeExceptionally(unavailable());
      else body.complete(bytes.toByteArray());
    }

    void cancel() {
      Flow.Subscription current = subscription;
      if (current != null) current.cancel();
      body.completeExceptionally(unavailable());
    }
  }
}
