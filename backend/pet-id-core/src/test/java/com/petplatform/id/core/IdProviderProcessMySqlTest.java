package com.petplatform.id.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class IdProviderProcessMySqlTest {
    @Test void livePriorJvmIsRejectedThenHaltedJvmAllowsFreshRangeAndWarmingDoesNotBurnH() throws Exception {
        Process old = null;
        Process fresh = null;
        var db = new MySqlIdTestDatabase();
        var readers = Executors.newCachedThreadPool();
        try {
            db.seedVirgin(17);
            old = start("hold", db, List.of());
            Process priorProcess = old;
            Instant recordedStart = old.info().startInstant().orElseThrow();
            var oldReader = new BufferedReader(new InputStreamReader(old.getInputStream(), StandardCharsets.UTF_8));
            List<String> oldLines = new ArrayList<>();
            String[] ready = readUntil(readers, oldReader, "READY ", oldLines).split(" ");
            assertEquals(old.pid(), Long.parseLong(ready[2]));
            assertEquals(recordedStart, Instant.parse(ready[3]));
            UUID oldIncarnation = UUID.fromString(ready[4]);
            long oldFence = Long.parseLong(ready[5]);
            long oldHigh = Long.parseLong(ready[7]);
            long oldId = Long.parseLong(ready[8]);
            PreviousJvmExitVerifier exitProof = previous -> {
                if (previous.nodeId() != 17 || !oldIncarnation.equals(previous.incarnation())
                        || previous.fence() != oldFence || previous.reservedThrough() < oldHigh
                        || !db.initializationRef(17).equals(previous.initializationRef())) {
                    throw new IllegalStateException("Evidence is not bound to this previous grant");
                }
                if (priorProcess.pid() != Long.parseLong(ready[2]) || !recordedStart.equals(Instant.parse(ready[3]))
                        || priorProcess.isAlive()) throw new IllegalStateException("Bound prior JVM still running");
                if (priorProcess.exitValue() != 23) throw new IllegalStateException("No expected halt exit evidence");
                ProcessHandle.of(priorProcess.pid()).ifPresent(handle -> {
                    if (handle.isAlive() && handle.info().startInstant().filter(recordedStart::equals).isPresent()) {
                        throw new IllegalStateException("Original process identity is still alive");
                    }
                });
            };
            assertTrue(old.isAlive());
            assertThrows(IllegalStateException.class, () -> new JdbcSnowflakeNodeStore(db.dataSource())
                    .acquire(new SnowflakeProviderSettings(17), UUID.randomUUID(), exitProof));
            assertEquals(oldHigh, db.highWater(17));
            send(old, "HALT");
            assertTrue(old.waitFor(3, TimeUnit.SECONDS));
            assertEquals(23, old.exitValue());
            // Fixture-only expiry injection after confirmed exit; never reset H/fence or infer death from lease.
            db.jdbc().update("UPDATE snowflake_worker_state SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE node_id=17");
            fresh = start("recover", db, List.of(oldIncarnation.toString(), Long.toString(oldFence),
                    Long.toString(old.pid()), recordedStart.toString()));
            var freshReader = new BufferedReader(new InputStreamReader(fresh.getInputStream(), StandardCharsets.UTF_8));
            List<String> freshLines = new ArrayList<>();
            String[] verify = readUntil(readers, freshReader, "VERIFY ", freshLines).split(" ");
            exitProof.verify(new PreviousJvmExitVerifier.PreviousJvm(17, UUID.fromString(verify[1]),
                    Long.parseLong(verify[2]), db.initializationRef(17), Long.parseLong(verify[3])));
            send(fresh, "EXIT_VERIFIED:" + oldIncarnation + ":" + old.pid() + ":" + recordedStart);
            String[] recovered = readUntil(readers, freshReader, "READY ", freshLines).split(" ");
            assertTrue(fresh.waitFor(3, TimeUnit.SECONDS));
            assertEquals(0, fresh.exitValue());
            assertTrue(freshLines.stream().anyMatch(line -> line.startsWith("WARMING ")),
                    "must actually exercise waiting for the old reserved boundary");
            assertNotEquals(oldIncarnation, UUID.fromString(recovered[4]));
            assertEquals(oldFence + 1, Long.parseLong(recovered[5]));
            assertTrue(Long.parseLong(recovered[6]) > oldHigh);
            assertTrue(Long.parseLong(recovered[8]) > oldId);
            assertEquals(Long.parseLong(recovered[7]), db.highWater(17));
            System.out.println("REAL_JVM_EXIT_BOUND oldPid=" + old.pid() + " start=" + recordedStart
                    + " oldIncarnation=" + oldIncarnation + " halt=23 freshPid=" + fresh.pid());
        } finally {
            try {
                stopOwnedChild(fresh);
                stopOwnedChild(old);
            } finally {
                readers.shutdownNow();
                db.close();
            }
        }
    }

    private static Process start(String mode, MySqlIdTestDatabase db, List<String> extra) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        List<String> command = new ArrayList<>(List.of(java, "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                IdProviderProcessProbe.class.getName(), mode, db.databaseUrl(), "17", db.initializationRef(17)));
        command.addAll(extra);
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    private static String readUntil(ExecutorService readers, BufferedReader reader, String prefix, List<String> lines) throws Exception {
        return readers.submit(() -> {
            for (String line; (line = reader.readLine()) != null;) {
                lines.add(line);
                System.out.println("ID_PROCESS " + line);
                if (line.startsWith(prefix)) return line;
            }
            throw new IllegalStateException("Child exited before " + prefix + ": " + lines);
        }).get(12, TimeUnit.SECONDS);
    }

    private static void send(Process process, String value) throws Exception {
        process.getOutputStream().write((value + "\n").getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
    }

    private static void stopOwnedChild(Process process) throws Exception {
        if (process != null && process.isAlive()) {
            process.destroy();
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(3, TimeUnit.SECONDS));
            }
        }
    }
}
