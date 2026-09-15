package com.petplatform.thirdparty.biz.application;

import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.thirdparty.biz.infrastructure.oss.OssConnection;
import com.petplatform.thirdparty.biz.infrastructure.oss.S3OssAssetClient;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * CLI entry for the one-click re-import. Arguments: <jdbcUrl> <dbUser> <dbPass> then
 * category=directory pairs (repeatable). Env: OSS_* (see ops/oss.env.local via the
 * scripts/oss-sync-assets.ps1 wrapper). Prints a report and never logs secrets.
 */
public final class OssAssetSyncCli {

    public static void main(String[] args) throws Exception {
        if (args.length > 1 && "--presign".equals(args[0])) {
            presign(args);
            return;
        }
        if (args.length < 4) {
            System.err.println("usage: OssAssetSyncCli <jdbcUrl> <dbUser> <dbPass> category=dir [category=dir ...]");
            System.exit(2);
        }
        DataSource dataSource = new DriverManagerDataSource(args[0], args[1], args[2]);
        OssConnection connection = OssConnection.fromEnv();
        SnowflakeIdGenerator ids = new LocalSequenceIdGenerator();
        Map<String, Path> roots = new LinkedHashMap<>();
        for (int i = 3; i < args.length; i++) {
            String pair = args[i];
            int eq = pair.indexOf('=');
            if (eq <= 0 || eq == pair.length() - 1) {
                throw new IllegalArgumentException("bad root pair (expected category=dir): " + pair);
            }
            roots.put(pair.substring(0, eq), Path.of(pair.substring(eq + 1)));
        }
        long minBytes = Long.parseLong(System.getenv().getOrDefault("OSS_SYNC_MIN_BYTES", "51200"));
        try (S3OssAssetClient client = new S3OssAssetClient(connection)) {
            var report = new OssAssetSyncService(client, dataSource, ids).sync(roots, minBytes);
            System.out.println("OSS sync complete: scanned=" + report.scanned()
                    + " uploaded=" + report.uploaded() + " unchanged=" + report.unchanged()
                    + " retired=" + report.retired() + " errors=" + report.errors().size());
            report.errors().forEach(error -> System.out.println("  error: " + error));
            if (!report.errors().isEmpty()) System.exit(1);
        }
    }

    /** --presign <jdbcUrl> <dbUser> <dbPass> <assetKey...>: print a signed URL per key. */
    private static void presign(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("usage: OssAssetSyncCli --presign <jdbcUrl> <dbUser> <dbPass> <assetKey...>");
            System.exit(2);
        }
        DataSource dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(args[1], args[2], args[3]);
        long window = Long.parseLong(System.getenv().getOrDefault("OSS_PRESIGN_WINDOW_SECONDS", "86400"));
        try (var service = new PresignedAssetUrlService(OssConnection.fromEnv(), dataSource,
                new LocalSequenceIdGenerator(), java.time.Clock.systemUTC(), window)) {
            for (int i = 4; i < args.length; i++) {
                var signed = service.presign(args[i]);
                System.out.println(signed.assetKey() + " | expiresAt=" + signed.expiresAtEpochSeconds()
                        + " | " + signed.url());
            }
        }
    }

}
