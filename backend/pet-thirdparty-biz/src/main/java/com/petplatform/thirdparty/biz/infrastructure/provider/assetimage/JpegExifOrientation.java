package com.petplatform.thirdparty.biz.infrastructure.provider.assetimage;

/** Reads only the bounded IFD0 orientation SHORT; no thumbnail, GPS or arbitrary TIFF values. */
final class JpegExifOrientation {
  private JpegExifOrientation() {}

  static int read(byte[] jpeg) {
    int position = 2, orientation = 1;
    boolean found = false;
    while (position < jpeg.length) {
      if ((jpeg[position++] & 255) != 255) throw invalid();
      while (position < jpeg.length && (jpeg[position] & 255) == 255) position++;
      if (position >= jpeg.length) throw invalid();
      int marker = jpeg[position++] & 255;
      if (marker == 0xda || marker == 0xd9) break; // Never parse entropy-coded image data.
      if (marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
      if (position + 2 > jpeg.length) throw invalid();
      int length = ((jpeg[position] & 255) << 8) | (jpeg[position + 1] & 255);
      if (length < 2 || length > jpeg.length - position) throw invalid();
      int start = position + 2, end = position + length;
      if (marker == 0xe1
          && end - start >= 6
          && jpeg[start] == 'E'
          && jpeg[start + 1] == 'x'
          && jpeg[start + 2] == 'i'
          && jpeg[start + 3] == 'f'
          && jpeg[start + 4] == 0
          && jpeg[start + 5] == 0) {
        Integer value = readIfd(jpeg, start + 6, end);
        if (value != null) {
          if (found) throw invalid();
          found = true;
          orientation = value;
        }
      }
      position = end;
    }
    return orientation;
  }

  private static Integer readIfd(byte[] source, int start, int end) {
    if (end - start < 8) throw invalid();
    boolean little = source[start] == 'I' && source[start + 1] == 'I';
    if (!little && !(source[start] == 'M' && source[start + 1] == 'M')) throw invalid();
    if (number(source, start + 2, 2, little) != 42) throw invalid();
    long offset = number(source, start + 4, 4, little);
    if (offset < 8 || offset > end - start - 2) throw invalid();
    int ifd = start + (int) offset;
    int count = (int) number(source, ifd, 2, little);
    if (count > (end - ifd - 2 - 4) / 12 || end - ifd < 6) throw invalid();
    Integer orientation = null;
    for (int index = 0; index < count; index++) {
      int entry = ifd + 2 + index * 12;
      if (number(source, entry, 2, little) != 0x0112) continue;
      if (orientation != null
          || number(source, entry + 2, 2, little) != 3
          || number(source, entry + 4, 4, little) != 1) throw invalid();
      int value = (int) number(source, entry + 8, 2, little);
      if (value < 1 || value > 8) throw invalid();
      orientation = value;
    }
    return orientation;
  }

  private static long number(byte[] source, int offset, int bytes, boolean little) {
    long value = 0;
    for (int index = 0; index < bytes; index++) {
      int shift = little ? index * 8 : (bytes - index - 1) * 8;
      value |= (long) (source[offset + index] & 255) << shift;
    }
    return value;
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Malformed JPEG orientation metadata");
  }
}
