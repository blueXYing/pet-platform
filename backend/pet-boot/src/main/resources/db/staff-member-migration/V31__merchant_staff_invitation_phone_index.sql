-- Pet Platform V1.0 merchant staff binding schema v0.1 delta (STA-B; SQL54).
-- MySQL 8.0+. Isolated opt-in Flyway delta mirroring docs/03-database/54-Merchant-Staff-Binding-Schema-v0.1.sql.
-- User ruling 2026-10-07 (contract 54 §7 amendment): index the invitation phone so the
-- employee-side list (GET /api/v1/c/staff/invitations) seeks instead of scanning windows.
-- V30 created the table and is already merged; this delta only adds the access-path index.
-- No automatic migration, legacy backfill or production enablement.

-- Employee-list access path: phone equality + id DESC backward-scan pagination. The explicit
-- (phone, id) composite self-documents the path; InnoDB stores the PK in the secondary index
-- either way, so the explicit form costs nothing extra. The phone column stores the plaintext
-- 11-digit registered mobile (confirm-time equality fact, V30), so plain index equality holds.
ALTER TABLE merchant_member_invitation
    ADD KEY idx_mer_member_inv_phone (phone, id);
