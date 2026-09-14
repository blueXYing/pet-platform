package com.petplatform.admin.biz.task;

import com.petplatform.admin.biz.application.AdminAuthFailure;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Observable durable component sink, NOT a production WORM/compliance storage claim. */
public final class FileAdminAuditSink implements AdminAuditSink {
  private static final int MAGIC = 0x41554431, MAX_FRAME = 8192;
  private final Path file;

  public FileAdminAuditSink(Path file) {
    this.file = Objects.requireNonNull(file).toAbsolutePath().normalize();
  }

  @Override
  public synchronized void append(Entry entry) {
    try {
      Path parent = file.getParent();
      if (parent == null || !Files.isDirectory(parent) || Files.isSymbolicLink(file))
        throw AdminAuthFailure.unavailable();
      Path checkpoint = file.resolveSibling(file.getFileName() + ".checkpoint");
      if (Files.isSymbolicLink(checkpoint)) throw AdminAuthFailure.unavailable();
      boolean checkpointExists = Files.exists(checkpoint);
      if (!checkpointExists && Files.exists(file) && Files.size(file) > 0)
        throw AdminAuthFailure.unavailable();
      try (FileChannel channel =
              FileChannel.open(
                  file,
                  StandardOpenOption.CREATE,
                  StandardOpenOption.READ,
                  StandardOpenOption.WRITE);
          FileLock lock = channel.tryLock();
          FileChannel state =
              FileChannel.open(
                  checkpoint,
                  StandardOpenOption.CREATE,
                  StandardOpenOption.READ,
                  StandardOpenOption.WRITE)) {
        if (lock == null) throw AdminAuthFailure.unavailable();
        byte[] payload = encode(entry);
        byte[] previous = new byte[32];
        long size = channel.size(), offset = 0;
        boolean found = false;
        Checkpoint ack = readCheckpoint(state);
        if (ack.length > size) throw AdminAuthFailure.unavailable();
        if (ack.length == 0 && !MessageDigest.isEqual(ack.hash, new byte[32]))
          throw AdminAuthFailure.unavailable();
        if (size > 64L * 1024 * 1024) throw AdminAuthFailure.unavailable();
        boolean ackSeen = ack.length == 0;
        while (offset < size) {
          ByteBuffer header = ByteBuffer.allocate(8);
          readFully(channel, header);
          header.flip();
          if (header.getInt() != MAGIC) throw AdminAuthFailure.unavailable();
          int n = header.getInt();
          if (n < 8 || n > MAX_FRAME || offset + 8L + n + 32 > size)
            throw AdminAuthFailure.unavailable();
          ByteBuffer value = ByteBuffer.allocate(n + 32);
          readFully(channel, value);
          byte[] bytes = value.array();
          byte[] old = Arrays.copyOf(bytes, n), hash = Arrays.copyOfRange(bytes, n, n + 32);
          if (!MessageDigest.isEqual(hash, hash(previous, old)))
            throw AdminAuthFailure.unavailable();
          long id = ByteBuffer.wrap(old).getLong();
          if (id == entry.id()) {
            if (found || !MessageDigest.isEqual(old, payload)) throw AdminAuthFailure.unavailable();
            found = true;
          }
          previous = hash;
          offset += 8L + n + 32;
          if (offset == ack.length) {
            if (!MessageDigest.isEqual(ack.hash, hash)) throw AdminAuthFailure.unavailable();
            ackSeen = true;
          }
        }
        if (!ackSeen) throw AdminAuthFailure.unavailable();
        if (found) {
          channel.force(true);
          saveCheckpoint(state, size, previous);
          return;
        }
        byte[] tail = hash(previous, payload);
        ByteBuffer frame = ByteBuffer.allocate(8 + payload.length + 32);
        frame.putInt(MAGIC).putInt(payload.length).put(payload).put(tail).flip();
        channel.position(size);
        while (frame.hasRemaining()) channel.write(frame);
        channel.force(true);
        saveCheckpoint(state, channel.size(), tail);
      }
    } catch (IOException | GeneralSecurityException | OverlappingFileLockException e) {
      throw AdminAuthFailure.unavailable();
    }
  }

  private static void readFully(FileChannel c, ByteBuffer b) throws IOException {
    while (b.hasRemaining()) if (c.read(b) < 0) throw new EOFException();
  }

  private static byte[] hash(byte[] previous, byte[] payload) throws GeneralSecurityException {
    MessageDigest d = MessageDigest.getInstance("SHA-256");
    d.update(previous);
    return d.digest(payload);
  }

  private record Checkpoint(long length, byte[] hash) {}

  private static Checkpoint readCheckpoint(FileChannel state)
      throws IOException, GeneralSecurityException {
    if (state.size() % 72 != 0 || state.size() > 64L * 1024 * 1024)
      throw AdminAuthFailure.unavailable();
    state.position(0);
    Checkpoint last = new Checkpoint(0, new byte[32]);
    while (state.position() < state.size()) {
      ByteBuffer b = ByteBuffer.allocate(72);
      readFully(state, b);
      byte[] data = b.array(),
          body = Arrays.copyOf(data, 40),
          check = Arrays.copyOfRange(data, 40, 72);
      if (!MessageDigest.isEqual(check, hash(new byte[32], body)))
        throw AdminAuthFailure.unavailable();
      long n = ByteBuffer.wrap(body).getLong();
      if (n < last.length) throw AdminAuthFailure.unavailable();
      last = new Checkpoint(n, Arrays.copyOfRange(body, 8, 40));
    }
    return last;
  }

  private static void saveCheckpoint(FileChannel state, long length, byte[] tail)
      throws IOException, GeneralSecurityException {
    Checkpoint old = readCheckpoint(state);
    if (old.length == length && MessageDigest.isEqual(old.hash, tail)) {
      state.force(true);
      return;
    }
    byte[] body = ByteBuffer.allocate(40).putLong(length).put(tail).array();
    ByteBuffer record = ByteBuffer.allocate(72).put(body).put(hash(new byte[32], body));
    record.flip();
    state.position(state.size());
    while (record.hasRemaining()) state.write(record);
    state.force(true);
  }

  private static byte[] encode(Entry e) throws IOException {
    if (e.id() < 1
        || e.action() == null
        || !e.action().matches("[A-Z_]{1,64}")
        || e.reason() == null
        || e.reason().length() > 500) throw AdminAuthFailure.invalid();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeLong(e.id());
      out.writeLong(e.actorId() == null ? 0 : e.actorId());
      out.writeLong(e.attemptId() == null ? 0 : e.attemptId());
      out.writeUTF(e.action());
      out.writeLong(e.resourceId() == null ? 0 : e.resourceId());
      out.writeUTF(e.outcome());
      out.writeUTF(e.reason());
      out.writeLong(e.occurredAt().toEpochMilli());
      out.writeUTF(e.actorReference());
    }
    return bytes.toByteArray();
  }

  @Override
  public String toString() {
    return "FileAdminAuditSink[configured]";
  }
}
