package com.petplatform.thirdparty.biz.infrastructure.provider.assetimage;

import com.petplatform.thirdparty.biz.application.port.PrivateAssetWatermarkRenderer;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.time.format.DateTimeFormatter;

/** Tiles trusted audit identifiers across each rendered read; never embeds a bearer token. */
public final class ImageIoPrivateAssetWatermarkRenderer implements PrivateAssetWatermarkRenderer {
  @Override
  public RenderedImage render(byte[] source, String mediaType, Watermark watermark) {
    if (watermark == null
        || watermark.operatorId() == null
        || watermark.applicationId() == null
        || !watermark.operatorId().matches("[1-9][0-9]{0,18}")
        || !watermark.applicationId().matches("[1-9][0-9]{0,18}")
        || watermark.renderedAt() == null) {
      throw new IllegalArgumentException("Trusted watermark context required");
    }
    var decoded = ImageIoPrivateAssetImageNormalizer.decode(source, mediaType);
    var image =
        new java.awt.image.BufferedImage(
            Math.max(480, decoded.getWidth()),
            Math.max(160, decoded.getHeight()),
            java.awt.image.BufferedImage.TYPE_INT_RGB);
    var graphics = image.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
      graphics.drawImage(decoded, 0, 0, null);
      decoded.flush();
      graphics.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      int size = Math.max(8, Math.min(24, image.getWidth() / 36));
      graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size));
      String[] lines = {
        "REVIEW ONLY",
        "OP " + watermark.operatorId(),
        "APP " + watermark.applicationId(),
        DateTimeFormatter.ISO_INSTANT.format(watermark.renderedAt().toInstant())
      };
      int tileWidth = Math.max(160, size * 23), tileHeight = size * 7;
      for (int y = 0; y < image.getHeight(); y += tileHeight) {
        for (int x = 0; x < image.getWidth(); x += tileWidth) {
          for (int line = 0; line < lines.length; line++) {
            int baseline = y + (line + 1) * (size + 2);
            graphics.setColor(new Color(255, 255, 255, 150));
            graphics.drawString(lines[line], x + 5, baseline + 1);
            graphics.setColor(new Color(30, 30, 30, 125));
            graphics.drawString(lines[line], x + 4, baseline);
          }
        }
      }
      return new RenderedImage(ImageIoPrivateAssetImageNormalizer.encode(image), "image/png");
    } finally {
      graphics.dispose();
      image.flush();
    }
  }
}
