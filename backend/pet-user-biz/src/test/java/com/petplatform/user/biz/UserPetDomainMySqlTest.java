package com.petplatform.user.biz;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.user.api.command.PetCommands.CreatePet;
import com.petplatform.user.api.command.PetCommands.DeletePet;
import com.petplatform.user.api.command.PetCommands.UpdatePet;
import com.petplatform.user.api.dto.PetSnapshotDTO;
import com.petplatform.user.api.dto.PetView;
import com.petplatform.user.api.query.PetSnapshotQuery;
import com.petplatform.user.api.query.UserIdQuery;
import com.petplatform.user.api.query.UserQueryApi;
import com.petplatform.user.biz.apiimpl.UserQueryApiImpl;
import com.petplatform.user.biz.application.PetService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** W2-USR-001~004 over a real isolated MySQL: ownership anti-enumeration, snapshot copies, delete semantics, idempotency. */
class UserPetDomainMySqlTest {
    private final AtomicLong ids = new AtomicLong(700_000);
    private static final String USER_A = "600001";
    private static final String USER_B = "600002";

    private PetService service(MySqlUserDomainTestDatabase db) {
        return new PetService(db.dataSource(), ids::incrementAndGet);
    }

    private static CommandContext userContext(String userId, String requestId) {
        return new CommandContext(requestId, "trace-" + requestId, OperatorType.USER, userId, "C_MINIAPP");
    }

    private CreatePet create(String requestId, String name, String weightKg, boolean defaultPet) {
        return new CreatePet(userContext(USER_A, requestId), name, "DOG", "柯基",
                LocalDate.of(2023, 5, 1), "MALE",
                weightKg == null ? null : new BigDecimal(weightKg),
                "NEUTERED", "COMPLETE", "对鸡肉过敏", null, defaultPet);
    }

    @Test void othersPetsAreIndistinguishableFromMissing_W2USR001() throws Exception {
        try (var db = new MySqlUserDomainTestDatabase()) {
            db.seedUser(600_001L, "13800000001", "ACTIVE");
            db.seedUser(600_002L, "13800000002", "ACTIVE");
            var service = service(db);
            PetView created = service.createPet(create(UUID.randomUUID().toString(), "豆豆", "12.50", false));
            assertEquals("ACTIVE", created.status());

            // user B never learns whether the pet exists.
            ApiException foreign = assertThrows(ApiException.class,
                    () -> service.getPet(created.petId(), USER_B));
            assertEquals("PET_NOT_FOUND", foreign.code());
            ApiException foreignUpdate = assertThrows(ApiException.class, () -> service.updatePet(
                    new UpdatePet(userContext(USER_B, UUID.randomUUID().toString()), created.petId(),
                            "抢注", null, null, null, null, null, null, null, null, null)));
            assertEquals("PET_NOT_FOUND", foreignUpdate.code());
            ApiException foreignDelete = assertThrows(ApiException.class, () -> service.deletePet(
                    new DeletePet(userContext(USER_B, UUID.randomUUID().toString()), created.petId())));
            assertEquals("PET_NOT_FOUND", foreignDelete.code());

            // snapshot API enforces the owner too.
            UserQueryApi queries = new UserQueryApiImpl(db.dataSource());
            ApiException snapshot = assertThrows(ApiException.class,
                    () -> queries.getPetSnapshot(new PetSnapshotQuery(created.petId(), USER_B)));
            assertEquals("PET_NOT_FOUND", snapshot.code());
        }
    }

    @Test void invalidFieldsAreRejected_W2USR001() throws Exception {
        try (var db = new MySqlUserDomainTestDatabase()) {
            db.seedUser(600_001L, "13800000001", "ACTIVE");
            var service = service(db);
            assertThrows(IllegalArgumentException.class,
                    () -> service.createPet(create(UUID.randomUUID().toString(), "豆豆", "12.5", false)),
                    "one-decimal weight");
            assertThrows(IllegalArgumentException.class,
                    () -> service.createPet(new CreatePet(userContext(USER_A, UUID.randomUUID().toString()),
                            "豆豆", "DOG", null, LocalDate.now().plusDays(1), null, null, null, null, null, null, false)),
                    "future birth date");
            assertThrows(IllegalArgumentException.class,
                    () -> service.createPet(new CreatePet(userContext(USER_A, UUID.randomUUID().toString()),
                            " ", "DOG", null, null, null, null, null, null, null, null, false)),
                    "blank name");
            assertThrows(IllegalArgumentException.class,
                    () -> service.createPet(new CreatePet(userContext(USER_A, UUID.randomUUID().toString()),
                            "豆豆", "PARROT", null, null, null, null, null, null, null, null, false)),
                    "unknown pet type");
            assertThrows(IllegalArgumentException.class,
                    () -> service.createPet(new CreatePet(userContext(USER_A, UUID.randomUUID().toString()),
                            "豆豆", "DOG", null, null, null, null, null, null, null, "http://insecure", false)),
                    "non-https avatar");
            assertThrows(IllegalArgumentException.class,
                    () -> service.createPet(create(null, "豆豆", "12.50", false)),
                    "missing requestId");
            assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM user_pet", Integer.class));
        }
    }

    @Test void snapshotIsAnIndependentCopy_W2USR002() throws Exception {
        try (var db = new MySqlUserDomainTestDatabase()) {
            db.seedUser(600_001L, "13800000001", "ACTIVE");
            var service = service(db);
            PetView created = service.createPet(create(UUID.randomUUID().toString(), "豆豆", "12.50", false));

            UserQueryApi queries = new UserQueryApiImpl(db.dataSource());
            PetSnapshotDTO snapshot = queries.getPetSnapshot(new PetSnapshotQuery(created.petId(), USER_A));
            assertEquals("豆豆", snapshot.name());
            assertEquals(new BigDecimal("12.50"), snapshot.weightKg());
            assertEquals(USER_A, snapshot.ownerUserId());

            // Later master-data changes never touch a snapshot already persisted by an order.
            db.seedOrderPetSnapshot(710_000L, 720_000L, Long.parseLong(created.petId()), "豆豆", "12.50");
            service.updatePet(new UpdatePet(userContext(USER_A, UUID.randomUUID().toString()), created.petId(),
                    "豆豆改名", null, null, null, new BigDecimal("20.00"), null, null, null, null, null));
            assertEquals("豆豆", db.jdbc().queryForObject(
                    "SELECT pet_name FROM order_pet_snapshot WHERE order_id=720000", String.class),
                    "persisted order snapshot is untouched");
            assertEquals("豆豆改名", queries.getPetSnapshot(
                    new PetSnapshotQuery(created.petId(), USER_A)).name(), "fresh snapshot reflects master data");
        }
    }

    @Test void deleteSoftDeletesAndDefaultRulesHold_W2USR003() throws Exception {
        try (var db = new MySqlUserDomainTestDatabase()) {
            db.seedUser(600_001L, "13800000001", "ACTIVE");
            var service = service(db);
            PetView first = service.createPet(create(UUID.randomUUID().toString(), "一号", "10.00", true));
            PetView second = service.createPet(create(UUID.randomUUID().toString(), "二号", "20.00", true));

            // At most one default: the second claim atomically clears the first.
            assertEquals(0, db.jdbc().queryForObject(
                    "SELECT is_default FROM user_pet WHERE id=?", Integer.class, Long.parseLong(first.petId())));
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT is_default FROM user_pet WHERE id=?", Integer.class, Long.parseLong(second.petId())));

            db.seedOrderPetSnapshot(710_001L, 720_001L, Long.parseLong(second.petId()), "二号", "20.00");
            var receipt = service.deletePet(new DeletePet(userContext(USER_A, UUID.randomUUID().toString()), second.petId()));
            assertEquals("DISABLED", receipt.status());
            assertEquals(1, service.listActivePets(USER_A).size(), "deleted pet leaves the active list");
            assertThrows(ApiException.class, () -> service.getPet(second.petId(), USER_A));
            assertEquals("二号", db.jdbc().queryForObject(
                    "SELECT pet_name FROM order_pet_snapshot WHERE order_id=720001", String.class),
                    "order snapshot survives the delete");

            // Deleting the default leaves no default until the user picks one again.
            assertEquals(0, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM user_pet WHERE user_id=600001 AND is_default=1", Integer.class));

            // Frozen users cannot write.
            db.seedUser(600_003L, "13800000003", "FROZEN");
            ApiException frozen = assertThrows(ApiException.class, () -> service.createPet(new CreatePet(
                    userContext("600003", UUID.randomUUID().toString()), "冰", "CAT", null, null, null, null,
                    null, null, null, null, false)));
            assertEquals("USER_FROZEN", frozen.code());
            UserQueryApi queries = new UserQueryApiImpl(db.dataSource());
            assertFalse(queries.existsEnabledUser(new UserIdQuery("600003")));
            assertTrue(queries.existsEnabledUser(new UserIdQuery(USER_A)));
        }
    }

    @Test void requestIdBindsFirstReceipt_W2USR004() throws Exception {
        try (var db = new MySqlUserDomainTestDatabase()) {
            db.seedUser(600_001L, "13800000001", "ACTIVE");
            var service = service(db);
            String requestId = UUID.randomUUID().toString();

            PetView first = service.createPet(create(requestId, "豆豆", "12.50", false));
            PetView replay = service.createPet(create(requestId, "豆豆", "12.50", false));
            assertEquals(first.petId(), replay.petId(), "replay returns the first receipt");
            assertEquals(1, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM user_pet WHERE user_id=600001", Integer.class));

            ApiException conflict = assertThrows(ApiException.class,
                    () -> service.createPet(create(requestId, "改名", "12.50", false)));
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, conflict.code());

            // A different requestId is a different command: business rules decide.
            PetView other = service.createPet(create(UUID.randomUUID().toString(), "二号", "8.00", false));
            assertNotEquals(first.petId(), other.petId());
            assertEquals(2, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM user_pet WHERE user_id=600001", Integer.class));

            // Same-params replay returns the first receipt; same key with different params is a 409 (23 §5).
            String updateId = UUID.randomUUID().toString();
            PetView updated = service.updatePet(new UpdatePet(userContext(USER_A, updateId), first.petId(),
                    "新名字", null, null, null, new BigDecimal("15.00"), null, null, null, null, null));
            PetView updateReplay = service.updatePet(new UpdatePet(userContext(USER_A, updateId), first.petId(),
                    "新名字", null, null, null, new BigDecimal("15.00"), null, null, null, null, null));
            assertEquals(updated.name(), updateReplay.name());
            assertEquals(0, updated.weightKg().compareTo(updateReplay.weightKg()), "receipt keeps the weight value");
            ApiException updateConflict = assertThrows(ApiException.class, () -> service.updatePet(
                    new UpdatePet(userContext(USER_A, updateId), first.petId(),
                            "又改名", null, null, null, new BigDecimal("30.00"), null, null, null, null, null)));
            assertEquals(CommonApiCodes.IDEMPOTENCY_KEY_CONFLICT, updateConflict.code());
            assertEquals("新名字", db.jdbc().queryForObject(
                    "SELECT name FROM user_pet WHERE id=?", String.class, Long.parseLong(first.petId())),
                    "only the first execution hit the database");

            // Delete replay still hands back the first receipt after the pet is gone.
            String deleteId = UUID.randomUUID().toString();
            var deleted = service.deletePet(new DeletePet(userContext(USER_A, deleteId), first.petId()));
            var deleteReplay = service.deletePet(new DeletePet(userContext(USER_A, deleteId), first.petId()));
            assertEquals(deleted.petId(), deleteReplay.petId());
            assertEquals("DISABLED", deleteReplay.status());

            // Bindings are kept, never re-keyed: two creates, one update, one delete.
            assertEquals(4, db.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM command_idempotency WHERE status='SUCCEEDED'", Integer.class));
        }
    }
}
