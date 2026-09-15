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
        if (args.length < 3 || (args.length - 3) % 2 != 0) {
            System.err.println("usage: OssAssetSyncCli <jdbcUrl> <dbUser> <dbPass> [category=dir ...]");
            System.exit(2);
        }
        DataSource dataSource = new DriverManagerDataSource(args[0], args[1], args[2]);
        OssConnection connection = OssConnection.fromEnv();
        SnowflakeIdGenerator ids = new LocalSequenceIdGenerator();
        Map<String, Path> roots = new LinkedHashMap<>();
        for (int i = 3; i < args.length; i += 2) {
            roots.put(args[i], Path.of(args[i + 1]));
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

}
