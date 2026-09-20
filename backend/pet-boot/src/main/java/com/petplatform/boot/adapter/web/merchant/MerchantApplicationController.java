package com.petplatform.boot.adapter.web.merchant;

import static com.petplatform.boot.adapter.web.merchant.MerchantHttpRequests.*;
import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;

import com.petplatform.merchant.api.command.*;
import com.petplatform.merchant.api.dto.MerchantApplicationTypes.*;
import com.petplatform.merchant.api.query.MerchantApplicationQueryApi;
import jakarta.servlet.http.*;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(
    value = "/api/v1/c/merchant-applications",
    produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "pet.merchant.application", name = "enabled", havingValue = "true")
public final class MerchantApplicationController {
  private final MerchantApplicationCommandApi commands;
  private final MerchantApplicationQueryApi queries;

  public MerchantApplicationController(
      MerchantApplicationCommandApi commands, MerchantApplicationQueryApi queries) {
    this.commands = commands;
    this.queries = queries;
  }

  @ModelAttribute
  void noStore(HttpServletResponse response) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
  }

  @GetMapping("/current")
  Map<String, Object> current(HttpServletRequest req) {
    onlyParameters(req);
    return envelope(
        owner(queries.getCurrentDetail(new CurrentMerchantApplicationQuery(userQuery(req)))), req);
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> create(@RequestBody String json, HttpServletRequest req, HttpServletResponse response) {
    onlyParameters(req);
    Draft body = decode(json, Draft.class);
    ApplicationCommandOutcome outcome =
        commands.createDraftOutcome(
            new CreateMerchantApplicationCommand(body.command(), userCommand(req)));
    response.setStatus(outcome.created() ? 201 : 200);
    return envelope(receipt(outcome.receipt()), req);
  }

  @PutMapping(value = "/{applicationId}/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> save(
      @PathVariable String applicationId, @RequestBody String json, HttpServletRequest req) {
    onlyParameters(req);
    Save body = decode(json, Save.class);
    MerchantApplicationResult result =
        commands.saveDraft(
            new SaveMerchantApplicationDraftCommand(
                id(applicationId),
                version(body.expectedVersion()),
                body.draft().command(),
                userCommand(req)));
    return envelope(receipt(result), req);
  }

  @PostMapping(value = "/{applicationId}/submit", consumes = MediaType.APPLICATION_JSON_VALUE)
  Map<String, Object> submit(
      @PathVariable String applicationId, @RequestBody String json, HttpServletRequest req) {
    onlyParameters(req);
    Submit body = decode(json, Submit.class);
    MerchantApplicationResult result =
        commands.submit(
            new SubmitMerchantApplicationCommand(
                id(applicationId),
                version(body.expectedVersion()),
                id(body.revisionId()),
                userCommand(req)));
    return envelope(receipt(result), req);
  }
}
