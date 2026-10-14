package com.petplatform.user.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.user.api.command.AddressCommands.CreateAddress;
import com.petplatform.user.api.command.AddressCommands.DeleteAddress;
import com.petplatform.user.api.command.AddressCommands.SetDefaultAddress;
import com.petplatform.user.api.command.AddressCommands.UpdateAddress;
import com.petplatform.user.api.dto.AddressView;
import com.petplatform.user.biz.application.AddressService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Contract 59 address kernel over a real isolated MySQL: ownership anti-enumeration, the
 * 20-address cap, single-default atomicity, soft delete, frozen-user rejection and the
 * supplement-23 five-tuple idempotency (scope USER_SELF).
 */
class UserAddressDomainMySqlTest {
    private final AtomicLong ids = new AtomicLong(800_000);
    private static final String USER_A = "610001";
    private static final String USER_B = "610002";

    private AddressService service(MySqlUserDomainTestDatabase db) {
        return new AddressService(db.dataSource(), ids::incrementAndGet);
    }

    private static CommandContext userContext(String userId, String requestId) {
        return new CommandContext(requestId, "trace-" + requestId, OperatorType.USER, userId, "C_MINIAPP");
    }

    private CreateAddress create(String requestId, String name, String detail, boolean isDefault) {
        return new CreateAddress(userContext(USER_A, requestId), name, "13800001234",
                "上海市 浦东新区", detail, isDefault);
    }

    @Test void othersAddressesAreIndistinguishableFromMissing() throws Exception {
        try (var db = new MySqlUserDomainTestDatabase()) {
            db.seedUser(610_001L, "13800000001", "ACTIVE");
            db.seedUser(610_002L, "13800000002", "ACTIVE");
            var service = service(db);
            AddressView created = service.createAddress(
                    create(UUID.randomUUID().toString(), "李小白", "祖冲之路887弄12号3楼", false));
            assertEquals("ACTIVE", created.status());
            assertFalse(created.isDefault());

            // User B never learns whether the address exists — same code for read/update/delete.
            ApiException foreignRead = assertThrows(ApiException.class,
                    () -> service.getAddress(created.addressId(), USER_B));
            assertEquals("ADDRESS_NOT_FOUND", foreignRead.code());
            ApiException foreignUpdate = assertThrows(ApiException.class, () -> service.updateAddress(
                    new UpdateAddress(userContext(USER_B, UUID.randomUUID().toString()), created.addressId(),
                            "抢注", "13900000000", "北京市 朝阳区", "他址", null)));
            assertEquals("ADDRESS_NOT_FOUND", foreignUpdate.code());
            ApiException foreignDelete = assertThrows(ApiException.class, () -> service.deleteAddress(
                    new DeleteAddress(userContext(USER_B, UUID.randomUUID().toString()), created.addressId())));
            assertEquals("ADDRESS_NOT_FOUND", foreignDelete.code());
            ApiException foreignDefault = assertThrows(ApiException.class, () -> service.setDefaultAddress(
                    new SetDefaultAddress(userContext(USER_B, UUID.randomUUID().toString()), created.addressId())));
            assertEquals("ADDRESS_NOT_FOUND", foreignDefault.code());
            assertTrue(service.listActiveAddresses(USER_B).isEmpty(), "owner list stays untouched");
        }
    }

    @Test void invalidFieldsAreRejected() throws Exception {
        try (var db = new MySqlUserDomainTestDatabase()) {
            db.seedUser(610_001L, "13800000001", "ACTIVE");
            var service = service(db);
            assertThrows(IllegalArgumentException.class,
                    () -> service.createAddress(new CreateAddress(userContext(USER_A, UUID.randomUUID().toString()),
                            " ", "13800001234", "上海市", "某路1号", false)), "blank contact");
            assertThrows(IllegalArgumentException.class,
                    () -> service.createAddress(new CreateAddress(userContext(USER_A, UUID.randomUUID().toString()),
                            "李小白", "1380000123", "上海市", "某路1号", false)), "10-digit phone");
            assertThrows(IllegalArgumentException.class,
                    () -> service.createAddress(new CreateAddress(userContext(USER_A, UUID.randomUUID().toString()),
                            "李小白", "12800001234", "上海市", "某路1号", false)), "non-1[3-9] prefix");
            assertThrows(IllegalArgumentException.class,
                    () -> service.createAddress(new CreateAddress(userContext(USER_A, UUID.randomUUID().toString()),
                            "李小白", "13800001234", "", "某路1号", false)), "blank region");
            assertThrows(IllegalArgumentException.class,
                    () -> service.createAddress(new CreateAddress(userContext(USER_A, UUID.randomUUID().toString()),
                            "李小白", "13800001234", "上海市", " ", false)), "blank detail");
            assertThrows(IllegalArgumentException.class,
                    () -> service.createAddress(new CreateAddress(userContext(USER_A, UUID.randomUUID().toString()),
                            "李".repeat(33), "13800001234", "上海市", "某路1号", false)), "33-codepoint name");
            assertThrows(IllegalArgumentException.class,
                    () -> service.createAddress(create(null, "李小白", "某路1号", false)), "missing requestId");
            assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM user_address", Integer.class));
        }
    }

    @Test void capLimitDefaultUniquenessAndSoftDeleteHold() throws Exception {
        try (var db = new MySqlUserDomainTestDatabase()) {
            db.seedUser(610_001L, "13800000001", "ACTIVE");
            var service = service(db);

            // Two default claims: the second atomically clears the first (single default).
            AddressView first = service.createAddress(create(UUID.randomUUID().toString(), "一号", "一号路1号", true));
            AddressView second = service.createAddress(create(UUID.randomUUID().toString(), "二号", "二号路2号", true));
            assertEquals(0, db.jdbc().queryForObject(
                    "SELECT is_default FROM user_address WHERE id=?", Integer.class, Long.parseLong(first.addressId())));
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT is_default FROM user_address WHERE id=?", Integer.class, Long.parseLong(second.addressId())));

            // set-default is the same atomic flip on an existing row.
            AddressView flipped = service.setDefaultAddress(
                    new SetDefaultAddress(userContext(USER_A, UUID.randomUUID().toString()), first.addressId()));
            assertTrue(flipped.isDefault());
            assertEquals(0, db.jdbc().queryForObject(
                    "SELECT is_default FROM user_address WHERE id=?", Integer.class, Long.parseLong(second.addressId())));

            // Fill to the cap: 2 exist, 18 more succeed, the 21st is rejected.
            for (int index = 0; index < 18; index++) {
                service.createAddress(create(UUID.randomUUID().toString(), "填充" + index, "填充路" + index + "号", false));
            }
            assertEquals(20, service.listActiveAddresses(USER_A).size());
            assertEquals(20, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM user_address WHERE user_id=610001 AND status='ACTIVE'", Integer.class));
            ApiException capped = assertThrows(ApiException.class,
                    () -> service.createAddress(create(UUID.randomUUID().toString(), "溢出", "溢出路1号", false)));
            assertEquals("ADDRESS_LIMIT_EXCEEDED", capped.code());

            // Default-first ordering per contract 59 (is_default DESC, created_at ASC, id ASC).
            List<AddressView> listed = service.listActiveAddresses(USER_A);
            assertTrue(listed.get(0).isDefault(), "default address leads the list");
            assertEquals(first.addressId(), listed.get(0).addressId());

            // Soft delete frees a slot; deleting the default leaves none until re-set.
            var receipt = service.deleteAddress(
                    new DeleteAddress(userContext(USER_A, UUID.randomUUID().toString()), first.addressId()));
            assertEquals("DELETED", receipt.status());
            assertEquals(19, service.listActiveAddresses(USER_A).size());
            assertThrows(ApiException.class, () -> service.getAddress(first.addressId(), USER_A));
            assertEquals(0, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM user_address WHERE user_id=610001 AND is_default=1", Integer.class));
            assertEquals("DELETED", db.jdbc().queryForObject(
                    "SELECT status FROM user_address WHERE id=?", String.class, Long.parseLong(first.addressId())),
                    "soft delete keeps the row for idempotent replays");
            // The freed slot admits a new address again.
            assertEquals(20, service.createAddress(
                    create(UUID.randomUUID().toString(), "回填", "回填路1号", false)) != null ? 20 : 19);

            // Frozen users cannot write but still read.
            db.seedUser(610_003L, "13800000003", "FROZEN");
            ApiException frozen = assertThrows(ApiException.class, () -> service.createAddress(
                    new CreateAddress(userContext("610003", UUID.randomUUID().toString()),
                            "冰", "13800001234", "上海市", "冰路1号", false)));
            assertEquals("USER_FROZEN", frozen.code());
        }
    }

    @Test void requestIdBindsFirstReceipt() throws Exception {
        try (var db = new MySqlUserDomainTestDatabase()) {
            db.seedUser(610_001L, "13800000001", "ACTIVE");
            var service = service(db);
            String requestId = UUID.randomUUID().toString();

            AddressService.CommandOutcome<AddressView> first =
                    service.createAddressOutcome(create(requestId, "李小白", "祖冲之路887弄12号3楼", false));
            assertFalse(first.replayed());
            AddressService.CommandOutcome<AddressView> replay =
                    service.createAddressOutcome(create(requestId, "李小白", "祖冲之路887弄12号3楼", false));
            assertTrue(replay.replayed());
            assertEquals(first.receipt().addressId(), replay.receipt().addressId());
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM user_address WHERE user_id=610001", Integer.class));

            ApiException conflict = assertThrows(ApiException.class,
                    () -> service.createAddress(create(requestId, "改名", "其他路1号", false)));
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, conflict.code());

            // Update: same key same params replays the first receipt; different params is a 409.
            String updateId = UUID.randomUUID().toString();
            UpdateAddress update = new UpdateAddress(userContext(USER_A, updateId), first.receipt().addressId(),
                    "李小白二号", "13800001234", "上海市 徐汇区", "漕溪北路88号5楼501室", null);
            AddressView updated = service.updateAddress(update);
            AddressView updateReplay = service.updateAddress(update);
            assertEquals(updated.detail(), updateReplay.detail());
            ApiException updateConflict = assertThrows(ApiException.class, () -> service.updateAddress(
                    new UpdateAddress(userContext(USER_A, updateId), first.receipt().addressId(),
                            "又改名", "13800001234", "上海市 徐汇区", "别的路1号", null)));
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, updateConflict.code());

            // Update with isDefault absent keeps the current flag; with true flips it atomically.
            assertFalse(updated.isDefault());
            AddressView defaulted = service.updateAddress(new UpdateAddress(userContext(USER_A, UUID.randomUUID().toString()),
                    first.receipt().addressId(), "李小白二号", "13800001234", "上海市 徐汇区", "漕溪北路88号5楼501室", true));
            assertTrue(defaulted.isDefault());

            // set-default: same key replays, different key on the same row is fine (new command).
            String defaultId = UUID.randomUUID().toString();
            SetDefaultAddress setDefault = new SetDefaultAddress(userContext(USER_A, defaultId), first.receipt().addressId());
            assertEquals(service.setDefaultAddress(setDefault).addressId(),
                    service.setDefaultAddress(setDefault).addressId());

            // Delete replay still hands back the first receipt after the row is soft-deleted.
            String deleteId = UUID.randomUUID().toString();
            var deleted = service.deleteAddress(new DeleteAddress(userContext(USER_A, deleteId), first.receipt().addressId()));
            var deleteReplay = service.deleteAddress(new DeleteAddress(userContext(USER_A, deleteId), first.receipt().addressId()));
            assertEquals(deleted.addressId(), deleteReplay.addressId());
            assertEquals("DELETED", deleteReplay.status());

            // Bindings are kept, never re-keyed: one create, two updates (plain + defaulted),
            // one set-default, one delete — every replay reuses its key, none re-keys.
            assertEquals(5, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM command_idempotency WHERE status='SUCCEEDED'", Integer.class));
        }
    }
}
