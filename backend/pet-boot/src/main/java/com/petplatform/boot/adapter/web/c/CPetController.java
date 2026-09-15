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
            "sterilizationStatus", "vaccineStatus", "healthNote", "avatarUrl", "isDefault");
    private static final Set<String> UPDATE_FIELDS = Set.of(
            "name", "breedName", "birthDate", "sex", "weightKg",
            "sterilizationStatus", "vaccineStatus", "healthNote", "avatarUrl", "isDefault");

    private final PetService pets;

    public CPetController(PetService pets) {
        this.pets = pets;
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
                body.string("avatarUrl"), Boolean.TRUE.equals(body.bool("isDefault")));
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
                body.string("avatarUrl"), body.bool("isDefault"));
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

    // ------------------------------------------------------------------ mapping

    /** Wire form per HTTP10 §3.2: IDs as decimal String, weightKg as plain decimal String. */
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
