package com.petplatform.community.biz.application;

import com.petplatform.common.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatterBuilder;
import java.util.List;

/**
 * Bounded CSV rendering for the contract-65 v0.4 encyclopedia export (2026-10-19 user ruling
 * "继续扩展CCR"): two labeled sections — the full all-status category list (with its read-side
 * counters, projected by the kernel) then the full all-status breed list (list-card fields
 * only; the rich-text columns are excluded — the export is the maintenance surface's data,
 * not the C-end article body). Generation is byte-bounded at 1 MiB and fails closed with
 * ENCYCLOPEDIA_EXPORT_TOO_LARGE before any response body exists (the controller never
 * receives partial bytes). RFC 4180 quoting: fields containing comma/quote/newline are
 * double-quoted with quote doubling; the aliases/tags columns render the stored JSON string
 * verbatim so values stay byte-faithful to the dictionary.
 */
public final class EncyclopediaCsvDocuments {

    /** Kernel-projected category row with its live read-side counters. */
    public record CategoryRow(String categoryCode, String categoryName, String status, int sortNo,
                              long breedCount, long publishedBreedCount, long version,
                              LocalDateTime createdAt, LocalDateTime updatedAt) {}

    /** Kernel-projected breed row (list-card fields, dictionary-ordered). */
    public record BreedRow(String breedId, String categoryCode, String name,
                           String aliasesJson, String tagsJson, String summary, String coverUrl,
                           String bodyType, String status, int sortNo, long version,
                           LocalDateTime createdAt, LocalDateTime updatedAt) {}

    /** CRLF per RFC 4180; plain UTF-8 without BOM. */
    private static final String CRLF = "\r\n";
    private static final DateTimeFormatterBuilder ISO = new DateTimeFormatterBuilder()
            .appendInstant(3);

    private EncyclopediaCsvDocuments() {}

    static byte[] generate(List<CategoryRow> categories, List<BreedRow> breeds,
            String generatedAt) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        try {
            writer.write("# 宠物百科导出（分类+品种清单；生成时刻 " + generatedAt + "）" + CRLF);
            writer.write("# —— 分类清单 ——" + CRLF);
            writer.write("categoryCode,categoryName,status,sortNo,breedCount,publishedBreedCount,"
                    + "version,createdAt,updatedAt" + CRLF);
            for (CategoryRow row : categories) {
                writer.write(String.join(",",
                        field(row.categoryCode()),
                        field(row.categoryName()),
                        field(row.status()),
                        field(Integer.toString(row.sortNo())),
                        field(Long.toString(row.breedCount())),
                        field(Long.toString(row.publishedBreedCount())),
                        field(Long.toString(row.version())),
                        field(iso(row.createdAt())),
                        field(iso(row.updatedAt()))) + CRLF);
                bounded(out);
            }
            writer.write("# —— 品种清单 ——" + CRLF);
            writer.write("breedId,categoryCode,breedName,aliases,tags,summary,coverUrl,bodyType,"
                    + "status,sortNo,version,createdAt,updatedAt" + CRLF);
            for (BreedRow row : breeds) {
                writer.write(String.join(",",
                        field(row.breedId()),
                        field(row.categoryCode()),
                        field(row.name()),
                        field(row.aliasesJson()),
                        field(row.tagsJson()),
                        field(row.summary()),
                        field(row.coverUrl()),
                        field(row.bodyType()),
                        field(row.status()),
                        field(Integer.toString(row.sortNo())),
                        field(Long.toString(row.version())),
                        field(iso(row.createdAt())),
                        field(iso(row.updatedAt()))) + CRLF);
                bounded(out);
            }
            writer.flush();
        } catch (TooLarge overflow) {
            throw overflow.asApiException();
        } catch (Exception failure) {
            throw new IllegalStateException("Encyclopedia CSV generation failed", failure);
        }
        bounded(out);
        return out.toByteArray();
    }

    private static final class TooLarge extends RuntimeException {
        ApiException asApiException() {
            return new ApiException("ENCYCLOPEDIA_EXPORT_TOO_LARGE", "导出内容超过 1MB 上限");
        }
    }

    private static void bounded(ByteArrayOutputStream out) {
        if (out.size() > AdminEncyclopediaService.EXPORT_MAX_BYTES) throw new TooLarge();
    }

    /** RFC 4180: quote only when the field contains comma, quote, CR or LF; double the quotes. */
    private static String field(String value) {
        if (value == null) return "";
        boolean needsQuoting = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
        if (!needsQuoting) return value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String iso(LocalDateTime value) {
        if (value == null) return "";
        return ISO.toFormatter().format(value.toInstant(ZoneOffset.UTC));
    }
}
