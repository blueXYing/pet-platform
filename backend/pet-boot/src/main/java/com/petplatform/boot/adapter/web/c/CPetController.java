package com.petplatform.boot.adapter.web.c;

import static com.petplatform.boot.adapter.web.c.CHttpModels.Request;

import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.ApiResponse;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.user.api.command.PetCommands.CreatePet;
import com.petplatform.user.api.command.PetCommands.DeletePet;
import com.petplatform.user.api.command.PetCommands.UpdatePet;
import com.petplatform.user.api.dto.PetView;
import com.petplatform.user.biz.application.PetService;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/c/pets CRUD (HTTP10 §3.2): the PR24 PetService unchanged — this adapter only maps
 * the session principal into CommandContext(USER) and the wire fields into commands. POST is
 * 201 on first execution and 200 on same-key idempotent replay (supplement 23 §5).
 */
@RestController
@RequestMapping("/api/v1/c/pets")
@ConditionalOnProperty(prefix = "pet.auth.c", name = "enabled", havingValue = "true")
public class CPetController {

    private static final Set<String> CREATE_FIELDS = Set.of(
            "name", "petType", "breedName", "birthDate", "sex", "weightKg",
            "sterilizationStatus", "vaccineStatus", "healthNote", "avatarAssetId", "avatarUrl", "isDefault");
    private static final Set<String> UPDATE_FIELDS = Set.of(
            "name", "breedName", "birthDate", "sex", "weightKg",
            "sterilizationStatus", "vaccineStatus", "healthNote", "avatarAssetId", "avatarUrl", "isDefault");

    private final PetService pets;
    private final com.petplatform.user.biz.application.PetHealthRecordService healthRecords;

    public CPetController(PetService pets,
            com.petplatform.user.biz.application.PetHealthRecordService healthRecords) {
        this.pets = pets;
        this.healthRecords = healthRecords;
    }

    private static MiniSessionView session(HttpServletRequest req) {
        Object attribute = req.getAttribute(CBearerSessionFilter.VIEW);
        if (!(attribute instanceof MiniSessionView view)) {
            throw new IllegalArgumentException("Session view missing");
        }
        return view;
    }

    private static CommandContext context(HttpServletRequest req, MiniSessionView view) {
        return new CommandContext(
                CShared.requestId(req), CShared.trace(req), OperatorType.USER,
                view.userId(), "C_MINIAPP");
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest req) {
        MiniSessionView view = session(req);
        List<Map<String, Object>> items = pets.listActivePets(view.userId()).stream()
                .map(CPetController::toBody).toList();
        return ApiResponse.success(items, CShared.trace(req));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(
            @RequestBody Request body, HttpServletRequest req, HttpServletResponse response) {
        body.require(CREATE_FIELDS, "name", "petType");
        MiniSessionView view = session(req);
        CreatePet command = new CreatePet(
                context(req, view),
                body.string("name"), body.string("petType"), body.string("breedName"),
                date(body.string("birthDate")), body.string("sex"),
                weight(body.string("weightKg")), body.string("sterilizationStatus"),
                body.string("vaccineStatus"), body.string("healthNote"),
                body.string("avatarAssetId"), externalAvatar(body),
                Boolean.TRUE.equals(body.bool("isDefault")));
        PetService.CommandOutcome<PetView> outcome = pets.createPetOutcome(command);
        response.setStatus(outcome.replayed() ? 200 : 201);
        return ApiResponse.success(toBody(outcome.receipt()), CShared.trace(req));
    }

    @GetMapping("/{petId}")
    public ApiResponse<Map<String, Object>> get(@PathVariable String petId, HttpServletRequest req) {
        MiniSessionView view = session(req);
        return ApiResponse.success(toBody(pets.getPet(petId, view.userId())), CShared.trace(req));
    }

    @PutMapping("/{petId}")
    public ApiResponse<Map<String, Object>> update(
            @PathVariable String petId, @RequestBody Request body, HttpServletRequest req) {
        body.require(UPDATE_FIELDS, "name");
        MiniSessionView view = session(req);
        UpdatePet command = new UpdatePet(
                context(req, view),
                petId,
                body.string("name"), body.string("breedName"),
                date(body.string("birthDate")), body.string("sex"),
                weight(body.string("weightKg")), body.string("sterilizationStatus"),
                body.string("vaccineStatus"), body.string("healthNote"),
                body.string("avatarAssetId"), externalAvatar(body), body.bool("isDefault"));
        return ApiResponse.success(toBody(pets.updatePet(command)), CShared.trace(req));
    }

    @DeleteMapping("/{petId}")
    public ApiResponse<Map<String, Object>> delete(
            @PathVariable String petId, HttpServletRequest req) {
        MiniSessionView view = session(req);
        return ApiResponse.success(
                receipt(pets.deletePet(new DeletePet(context(req, view), petId))),
                CShared.trace(req));
    }

    // ---------------------------------------------- health records (contract 68)

    private static final Set<String> VACCINE_FIELDS = Set.of(
            "recordDate", "vaccineName", "doseNumber", "nextRecordDate", "note");
    private static final Set<String> DEWORM_FIELDS = Set.of(
            "recordDate", "dewormingType", "productName", "nextRecordDate", "note");

    /** Contract 68: PRD 5.1.3 vaccine record sub-resource; 201 first / 200 idempotent replay. */
    @PostMapping("/{petId}/vaccination-records")
    public ApiResponse<Map<String, Object>> createVaccination(
            @PathVariable String petId, @RequestBody Request body, HttpServletRequest req,
            HttpServletResponse response) {
        body.require(VACCINE_FIELDS, "recordDate", "vaccineName");
        MiniSessionView view = session(req);
        var command = new com.petplatform.user.api.command.PetHealthRecordCommands.CreateVaccinationRecord(
                context(req, view), petId, date(body.string("recordDate")),
                body.string("vaccineName"), integer(body.value("doseNumber"), "doseNumber"),
                date(body.string("nextRecordDate")), body.string("note"));
        var outcome = healthRecords.createVaccinationOutcome(command);
        response.setStatus(outcome.replayed() ? 200 : 201);
        return ApiResponse.success(recordBody(outcome.receipt()), CShared.trace(req));
    }

    @GetMapping("/{petId}/vaccination-records")
    public ApiResponse<List<Map<String, Object>>> listVaccination(
            @PathVariable String petId, HttpServletRequest req) {
        MiniSessionView view = session(req);
        return ApiResponse.success(
                healthRecords.listVaccinationRecords(petId, view.userId()).stream()
                        .map(CPetController::recordBody).toList(),
                CShared.trace(req));
    }

    /** Contract 68: PRD 5.1.3 deworming record sub-resource; 201 first / 200 idempotent replay. */
    @PostMapping("/{petId}/deworming-records")
    public ApiResponse<Map<String, Object>> createDeworming(
            @PathVariable String petId, @RequestBody Request body, HttpServletRequest req,
            HttpServletResponse response) {
        body.require(DEWORM_FIELDS, "recordDate", "dewormingType");
        MiniSessionView view = session(req);
        var command = new com.petplatform.user.api.command.PetHealthRecordCommands.CreateDewormingRecord(
                context(req, view), petId, date(body.string("recordDate")),
                body.string("dewormingType"), body.string("productName"),
                date(body.string("nextRecordDate")), body.string("note"));
        var outcome = healthRecords.createDewormingOutcome(command);
        response.setStatus(outcome.replayed() ? 200 : 201);
        return ApiResponse.success(recordBody(outcome.receipt()), CShared.trace(req));
    }

    @GetMapping("/{petId}/deworming-records")
    public ApiResponse<List<Map<String, Object>>> listDeworming(
            @PathVariable String petId, HttpServletRequest req) {
        MiniSessionView view = session(req);
        return ApiResponse.success(
                healthRecords.listDewormingRecords(petId, view.userId()).stream()
                        .map(CPetController::recordBody).toList(),
                CShared.trace(req));
    }

    /** Contract 68 wire form: ids decimal String, dates ISO yyyy-MM-dd, facts only. */
    private static Map<String, Object> recordBody(com.petplatform.user.api.dto.PetHealthRecordView record) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recordId", record.recordId());
        body.put("petId", record.petId());
        body.put("recordType", record.recordType());
        body.put("recordDate", record.recordDate() == null ? null : record.recordDate().toString());
        body.put("vaccineName", record.vaccineName());
        body.put("doseNumber", record.doseNumber());
        body.put("dewormingType", record.dewormingType());
        body.put("productName", record.productName());
        body.put("nextRecordDate", record.nextRecordDate() == null ? null : record.nextRecordDate().toString());
        body.put("note", record.note());
        body.put("status", record.status());
        return body;
    }

    private static Map<String, Object> recordBody(com.petplatform.user.api.command.PetHealthRecordCommands.CreateRecordReceipt receipt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("petId", receipt.petId());
        body.put("recordId", receipt.recordId());
        body.put("recordType", receipt.recordType());
        body.put("status", receipt.status());
        return body;
    }

    private static Integer integer(Object value, String field) {
        if (value == null) return null;
        Integer parsed;
        if (value instanceof Integer i) {
            parsed = i;
        } else if (value instanceof Long l && l == l.intValue()) {
            parsed = l.intValue();
        } else if (value instanceof String s && s.matches("[1-9][0-9]{0,2}")) {
            parsed = Integer.valueOf(s);
        } else {
            throw new IllegalArgumentException(field + " must be a positive integer 1..20");
        }
        if (parsed < 1 || parsed > 20) {
            throw new IllegalArgumentException(field + " must be a positive integer 1..20");
        }
        return parsed;
    }

    // ------------------------------------------------------------------ mapping

    /** Wire form per HTTP10 §3.2: IDs as decimal String, weightKg as plain decimal String. */
    /** Internal asset refs are accepted only via avatarAssetId, never as a client URL. */
    private static String externalAvatar(Request body) {
        String value = body.string("avatarUrl");
        if (value != null && value.startsWith("asset:")) {
            throw new IllegalArgumentException("avatarUrl must be an external https URL");
        }
        return value;
    }

    private static Map<String, Object> toBody(PetView pet) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("petId", pet.petId());
        body.put("name", pet.name());
        body.put("petType", pet.petType());
        body.put("breedName", pet.breedName());
        body.put("birthDate", pet.birthDate() == null ? null : pet.birthDate().toString());
        body.put("sex", pet.sex());
        // Validator guarantees two decimals; normalize the replayed receipt to the same scale.
        body.put("weightKg", pet.weightKg() == null ? null
                : pet.weightKg().setScale(2).toPlainString());
        body.put("sterilizationStatus", pet.sterilizationStatus());
        body.put("vaccineStatus", pet.vaccineStatus());
        body.put("healthNote", pet.healthNote());
        body.put("avatarUrl", pet.avatarUrl());
        body.put("isDefault", pet.isDefault());
        body.put("status", pet.status());
        return body;
    }

    private static Map<String, Object> receipt(com.petplatform.user.api.command.PetCommands.PetReceipt pet) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("petId", pet.petId());
        body.put("status", pet.status());
        body.put("isDefault", pet.isDefault());
        return body;
    }

    private static LocalDate date(String value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("birthDate must be yyyy-MM-dd");
        }
    }

    private static BigDecimal weight(String value) {
        if (value == null) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("weightKg must be a decimal string");
        }
    }
}
