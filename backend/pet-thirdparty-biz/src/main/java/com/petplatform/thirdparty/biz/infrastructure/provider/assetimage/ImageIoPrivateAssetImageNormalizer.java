package com.petplatform.thirdparty.biz.infrastructure.provider.assetimage;

import com.petplatform.thirdparty.biz.application.port.PrivateAssetImageNormalizer;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Bounded JPEG/PNG decode and metadata-free canonical PNG encoding. */
public final class ImageIoPrivateAssetImageNormalizer implements PrivateAssetImageNormalizer {
  static final int MAX_BYTES = 10 * 1024 * 1024;

  @Override
  public NormalizedImage normalize(byte[] source, String declaredMediaType) {
    BufferedImage decoded = decode(source, declaredMediaType);
    int orientation;
    try {
      orientation = "image/jpeg".equals(declaredMediaType) ? JpegExifOrientation.read(source) : 1;
    } catch (RuntimeException invalidMetadata) {
      decoded.flush();
      throw invalidMetadata;
    }
    int width = decoded.getWidth(), height = decoded.getHeight();
    boolean transpose = orientation >= 5;
    BufferedImage clean =
        new BufferedImage(
            transpose ? height : width, transpose ? width : height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = clean.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, clean.getWidth(), clean.getHeight());
      graphics.transform(
          switch (orientation) {
            case 2 -> new java.awt.geom.AffineTransform(-1, 0, 0, 1, width, 0);
            case 3 -> new java.awt.geom.AffineTransform(-1, 0, 0, -1, width, height);
            case 4 -> new java.awt.geom.AffineTransform(1, 0, 0, -1, 0, height);
            case 5 -> new java.awt.geom.AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new java.awt.geom.AffineTransform(0, 1, -1, 0, height, 0);
            case 7 -> new java.awt.geom.AffineTransform(0, -1, -1, 0, height, width);
            case 8 -> new java.awt.geom.AffineTransform(0, -1, 1, 0, 0, width);
            default -> new java.awt.geom.AffineTransform();
          });
      graphics.drawImage(decoded, 0, 0, null);
    } finally {
      graphics.dispose();
      decoded.flush();
    }
    try {
      return new NormalizedImage(encode(clean), "image/png", clean.getWidth(), clean.getHeight());
    } finally {
      clean.flush();
    }
  }

  static BufferedImage decode(byte[] source, String mediaType) {
    if (source == null || source.length == 0 || source.length > MAX_BYTES) throw invalid();
    boolean png =
        source.length >= 8
            && source[0] == (byte) 137
            && source[1] == 80
            && source[2] == 78
            && source[3] == 71
            && source[4] == 13
            && source[5] == 10
            && source[6] == 26
            && source[7] == 10;
    boolean jpeg =
        source.length >= 3
            && source[0] == (byte) 255
            && source[1] == (byte) 216
            && source[2] == (byte) 255;
    if (!(png && "image/png".equals(mediaType)) && !(jpeg && "image/jpeg".equals(mediaType)))
      throw invalid();
    try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(source))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw invalid();
      ImageReader reader = readers.next();
      try {
        reader.setInput(input, true, true);
        int width = reader.getWidth(0), height = reader.getHeight(0);
        if (width <= 0
            || height <= 0
            || width > 8192
            || height > 8192
            || (long) width * height > 16_000_000L) throw invalid();
        // Truncated JPEG decoders sometimes return a partial image with only a warning.
        reader.addIIOReadWarningListener(
            (ignored, warning) -> {
              throw invalid();
            });
        BufferedImage result = reader.read(0);
        if (result == null) throw invalid();
        return result;
      } finally {
        reader.dispose();
      }
    } catch (IOException | RuntimeException failure) {
      throw invalid();
    }
  }

  static byte[] encode(BufferedImage image) {
    var bytes =
        new ByteArrayOutputStream() {
          @Override
          public synchronized void write(int value) {
            if (count >= MAX_BYTES) throw invalid();
            super.write(value);
          }

          @Override
          public synchronized void write(byte[] value, int offset, int length) {
            if (length > MAX_BYTES - count) throw invalid();
            super.write(value, offset, length);
          }
        };
    try (var output = new javax.imageio.stream.MemoryCacheImageOutputStream(bytes)) {
      var writer = ImageIO.getImageWritersByFormatName("png").next();
      try {
        writer.setOutput(output);
        writer.write(
            null, new javax.imageio.IIOImage(image, null, null), writer.getDefaultWriteParam());
        output.flush();
      } finally {
        writer.dispose();
      }
      return bytes.toByteArray();
    } catch (IOException failure) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException(
        "Private material must be a complete bounded JPEG or PNG image");
  }
}
