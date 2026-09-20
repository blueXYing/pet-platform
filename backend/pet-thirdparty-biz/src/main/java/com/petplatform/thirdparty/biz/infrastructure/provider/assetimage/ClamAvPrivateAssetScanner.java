package com.petplatform.thirdparty.biz.infrastructure.provider.assetimage;

import com.petplatform.thirdparty.biz.application.port.PrivateAssetScanner;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Clamd INSTREAM over a trusted private network; no paths or scan responses are logged. */
public final class ClamAvPrivateAssetScanner implements PrivateAssetScanner {
  private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
  private final InetSocketAddress address;
  private final int timeoutMillis;

  public ClamAvPrivateAssetScanner(String host, int port, Duration timeout) {
    if (host == null
        || host.isBlank()
        || port < 1
        || port > 65535
        || timeout == null
        || timeout.toMillis() < 1
        || timeout.toMillis() > 60_000) {
      throw new IllegalArgumentException("Bounded private scanner endpoint required");
    }
    address = new InetSocketAddress(host, port);
    timeoutMillis = (int) timeout.toMillis();
  }

  @Override
  public ScanResult scan(byte[] content) {
    if (content == null || content.length == 0 || content.length > 10 * 1024 * 1024) {
      throw new IllegalArgumentException("Private material exceeds scan bounds");
    }
    // VERSION is required as persisted evidence that a database-backed engine is available.
    String version = exchange(null);
    if (!version.matches("ClamAV [0-9][A-Za-z0-9.+_-]{0,40}/[0-9]{1,12}/[ -~]{1,80}"))
      throw unavailable();
    String verdict = exchange(content);
    if ("stream: OK".equals(verdict)) return new ScanResult(true, version, "CLEAN");
    if (verdict.matches("stream: [ -~]{1,200} FOUND"))
      return new ScanResult(false, version, "MALWARE_FOUND");
    throw unavailable();
  }

  private String exchange(byte[] content) {
    try (Socket socket = new Socket()) {
      // Socket SO_TIMEOUT alone does not bound a blocked write. Closing at the deadline does.
      var expiry =
          DEADLINES.schedule(
              () -> {
                try {
                  socket.close();
                } catch (IOException ignored) {
                }
              },
              timeoutMillis,
              TimeUnit.MILLISECONDS);
      try {
        socket.connect(address, timeoutMillis);
        socket.setSoTimeout(timeoutMillis);
        var output = new DataOutputStream(socket.getOutputStream());
        output.write(
            (content == null ? "zVERSION\0" : "zINSTREAM\0").getBytes(StandardCharsets.US_ASCII));
        if (content != null) {
          for (int offset = 0; offset < content.length; offset += 8192) {
            int length = Math.min(8192, content.length - offset);
            output.writeInt(length);
            output.write(content, offset, length);
          }
          output.writeInt(0);
        }
        output.flush();
        var input = socket.getInputStream();
        var response = new ByteArrayOutputStream();
        for (int i = 0; i < 512; i++) {
          int next = input.read();
          if (next == 0) return response.toString(StandardCharsets.US_ASCII);
          if (next < 32 || next > 126) throw unavailable();
          response.write(next);
        }
        throw unavailable();
      } finally {
        expiry.cancel(false);
      }
    } catch (IOException failure) {
      // Do not leak endpoint, provider details, material or signatures into ordinary logs.
      throw unavailable();
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Private material scanner unavailable");
  }

  private static ScheduledThreadPoolExecutor deadlines() {
    var executor =
        new ScheduledThreadPoolExecutor(
            1,
            runnable -> {
              Thread thread = Executors.defaultThreadFactory().newThread(runnable);
              thread.setName("private-material-scan-deadline");
              thread.setDaemon(true);
              return thread;
            });
    executor.setRemoveOnCancelPolicy(true);
    return executor;
  }
}
