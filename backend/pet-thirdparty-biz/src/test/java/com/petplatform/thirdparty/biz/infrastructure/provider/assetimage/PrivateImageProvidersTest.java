package com.petplatform.thirdparty.biz.infrastructure.provider.assetimage;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.thirdparty.biz.application.port.PrivateAssetWatermarkRenderer.Watermark;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class PrivateImageProvidersTest {
  @Test
  void allEightExifOrientationsPreserveRectangularPixelCoordinatesAndStripMetadata()
      throws Exception {
    var source = new BufferedImage(48, 32, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 32; y++)
      for (int x = 0; x < 48; x++) {
        source.setRGB(x, y, new Color(x * 5, y * 7, (x + y) * 3).getRGB());
      }
    var encoded = new ByteArrayOutputStream();
    ImageIO.write(source, "jpeg", encoded);
    byte[] jpeg = encoded.toByteArray();
    var raw = ImageIO.read(new ByteArrayInputStream(jpeg));
    for (boolean little : new boolean[] {true, false})
      for (int orientation = 1; orientation <= 8; orientation++) {
        byte[] tagged = withOrientation(jpeg, orientation, little);
        var normalized = new ImageIoPrivateAssetImageNormalizer().normalize(tagged, "image/jpeg");
        var actual = ImageIO.read(new ByteArrayInputStream(normalized.content()));
        assertEquals(orientation >= 5 ? 32 : 48, actual.getWidth());
        assertEquals(orientation >= 5 ? 48 : 32, actual.getHeight());
        for (int y = 0; y < 32; y++)
          for (int x = 0; x < 48; x++) {
            int targetX =
                switch (orientation) {
                  case 2, 3 -> 47 - x;
                  case 5, 8 -> y;
                  case 6, 7 -> 31 - y;
                  default -> x;
                };
            int targetY =
                switch (orientation) {
                  case 3, 4 -> 31 - y;
                  case 5, 6 -> x;
                  case 7, 8 -> 47 - x;
                  default -> y;
                };
            assertEquals(
                raw.getRGB(x, y), actual.getRGB(targetX, targetY), "orientation " + orientation);
          }
        assertFalse(new String(normalized.content(), StandardCharsets.ISO_8859_1).contains("Exif"));
      }
  }

  @Test
  void malformedExifOrientationAndOutOfBoundsIfdFailClosed() throws Exception {
    byte[] jpeg = image("jpeg", 32, 16);
    for (int invalid : new int[] {0, 9, 65535}) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new ImageIoPrivateAssetImageNormalizer()
                  .normalize(withOrientation(jpeg, invalid, true), "image/jpeg"));
    }
    byte[] badOffset = withOrientation(jpeg, 1, true);
    Arrays.fill(badOffset, 16, 20, (byte) 255);
    assertThrows(
        IllegalArgumentException.class,
        () -> new ImageIoPrivateAssetImageNormalizer().normalize(badOffset, "image/jpeg"));
    byte[] badType = withOrientation(jpeg, 1, true);
    badType[24] = 4;
    assertThrows(
        IllegalArgumentException.class,
        () -> new ImageIoPrivateAssetImageNormalizer().normalize(badType, "image/jpeg"));
  }

  private static byte[] withOrientation(byte[] jpeg, int orientation, boolean little)
      throws Exception {
    var tiff =
        java.nio.ByteBuffer.allocate(26)
            .order(little ? java.nio.ByteOrder.LITTLE_ENDIAN : java.nio.ByteOrder.BIG_ENDIAN);
    tiff.put((byte) (little ? 'I' : 'M')).put((byte) (little ? 'I' : 'M'));
    tiff.putShort((short) 42).putInt(8).putShort((short) 1);
    tiff.putShort((short) 0x0112)
        .putShort((short) 3)
        .putInt(1)
        .putShort((short) orientation)
        .putShort((short) 0)
        .putInt(0);
    var result = new ByteArrayOutputStream();
    result.write(jpeg, 0, 2);
    result.write(new byte[] {(byte) 255, (byte) 225, 0, 34, 'E', 'x', 'i', 'f', 0, 0});
    result.write(tiff.array());
    result.write(jpeg, 2, jpeg.length - 2);
    return result.toByteArray();
  }

  @Test
  void normalizationDecodesJpegAndRemovesAppendedPayloadAndWatermarkChangesWithActor()
      throws Exception {
    byte[] jpeg = image("jpeg", 640, 480);
    byte[] source = Arrays.copyOf(jpeg, jpeg.length + 40);
    Arrays.fill(source, jpeg.length, source.length, (byte) 65);
    var result = new ImageIoPrivateAssetImageNormalizer().normalize(source, "image/jpeg");
    assertEquals("image/png", result.mediaType());
    assertEquals(640, result.width());
    assertEquals(480, result.height());
    assertNotNull(ImageIO.read(new ByteArrayInputStream(result.content())));
    var renderer = new ImageIoPrivateAssetWatermarkRenderer();
    var at = OffsetDateTime.parse("2026-09-20T10:00:00Z");
    var first = renderer.render(result.content(), "image/png", new Watermark("100", "300", at));
    var second = renderer.render(result.content(), "image/png", new Watermark("200", "300", at));
    assertFalse(Arrays.equals(result.content(), first.content()));
    assertFalse(Arrays.equals(first.content(), second.content()));
    assertNotNull(ImageIO.read(new ByteArrayInputStream(first.content())));
  }

  @Test
  void malformedMislabelledOversizedAndTruncatedImagesFailClosed() throws Exception {
    var normalizer = new ImageIoPrivateAssetImageNormalizer();
    byte[] png = image("png", 64, 64), jpeg = image("jpeg", 64, 64);
    assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(png, "image/jpeg"));
    assertThrows(
        IllegalArgumentException.class,
        () -> normalizer.normalize("<svg/>".getBytes(), "image/png"));
    assertThrows(
        IllegalArgumentException.class,
        () -> normalizer.normalize(new byte[10 * 1024 * 1024 + 1], "image/png"));
    assertThrows(
        IllegalArgumentException.class,
        () -> normalizer.normalize(Arrays.copyOf(png, 30), "image/png"));
    assertThrows(
        IllegalArgumentException.class,
        () -> normalizer.normalize(Arrays.copyOf(jpeg, jpeg.length / 2), "image/jpeg"));
    byte[] hugeHeader = png.clone();
    hugeHeader[16] = 0x7f;
    hugeHeader[17] = (byte) 0xff;
    assertThrows(
        IllegalArgumentException.class, () -> normalizer.normalize(hugeHeader, "image/png"));
  }

  @Test
  void tinyImagesStillHaveRoomForAuditWatermark() throws Exception {
    var result =
        new ImageIoPrivateAssetWatermarkRenderer()
            .render(
                image("png", 1, 1),
                "image/png",
                new Watermark("1", "2", OffsetDateTime.parse("2026-09-20T10:00:00Z")));
    var decoded = ImageIO.read(new ByteArrayInputStream(result.content()));
    assertTrue(decoded.getWidth() >= 480);
    assertTrue(decoded.getHeight() >= 160);
  }

  @Test
  void clamdSendsOriginalBytesAndRequiresCleanTerminatedVerdict() throws Exception {
    byte[] source = image("png", 32, 32);
    assertTrue(scan(source, "stream: OK\0").clean());
    assertFalse(scan(source, "stream: Test-Signature FOUND\0").clean());
    for (String reply :
        new String[] {"stream: limit exceeded ERROR\0", "stream: OK", "OK\0", "\0"}) {
      assertThrows(IllegalStateException.class, () -> scan(source, reply));
    }
  }

  @Test
  void clamdDeadlineClosesUnresponsiveSocket() throws Exception {
    try (var server = new ServerSocket(0);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var accepted =
          executor.submit(
              () -> {
                try (var socket = server.accept()) {
                  while (socket.getInputStream().read() >= 0) {}
                }
                return true;
              });
      var scanner =
          new ClamAvPrivateAssetScanner("127.0.0.1", server.getLocalPort(), Duration.ofMillis(150));
      assertTimeoutPreemptively(
          Duration.ofSeconds(3),
          () -> assertThrows(IllegalStateException.class, () -> scanner.scan(new byte[] {1})));
      assertTrue(accepted.get(2, TimeUnit.SECONDS));
    }
  }

  private static com.petplatform.thirdparty.biz.application.port.PrivateAssetScanner.ScanResult
      scan(byte[] source, String reply) throws Exception {
    try (var server = new ServerSocket(0);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var received =
          executor.submit(
              () -> {
                try (var version = server.accept()) {
                  assertEquals("zVERSION", record(version.getInputStream()));
                  version
                      .getOutputStream()
                      .write(
                          "ClamAV 1.4.0/27000/Sun Sep 20 10:00:00 2026\0"
                              .getBytes(StandardCharsets.US_ASCII));
                }
                try (var stream = server.accept()) {
                  assertEquals("zINSTREAM", record(stream.getInputStream()));
                  var input = new DataInputStream(stream.getInputStream());
                  var bytes = new ByteArrayOutputStream();
                  for (int length; (length = input.readInt()) != 0; ) {
                    assertTrue(length > 0 && length <= 8192);
                    bytes.write(input.readNBytes(length));
                  }
                  assertArrayEquals(source, bytes.toByteArray());
                  stream.getOutputStream().write(reply.getBytes(StandardCharsets.US_ASCII));
                }
                return true;
              });
      try {
        return new ClamAvPrivateAssetScanner(
                "127.0.0.1", server.getLocalPort(), Duration.ofSeconds(2))
            .scan(source);
      } finally {
        assertTrue(received.get(3, TimeUnit.SECONDS));
      }
    }
  }

  private static String record(java.io.InputStream input) throws Exception {
    var bytes = new ByteArrayOutputStream();
    for (int next; (next = input.read()) > 0; ) bytes.write(next);
    return bytes.toString(StandardCharsets.US_ASCII);
  }

  private static byte[] image(String format, int width, int height) throws Exception {
    var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    var graphics = image.createGraphics();
    graphics.setColor(Color.WHITE);
    graphics.fillRect(0, 0, width, height);
    graphics.dispose();
    var output = new ByteArrayOutputStream();
    ImageIO.write(image, format, output);
    return output.toByteArray();
  }
}
