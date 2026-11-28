package com.cardauth.auth.authz;

import com.cardauth.auth.authz.AuthorizationService.AuthorizationRequest;
import com.cardauth.auth.authz.AuthorizationService.AuthorizationResponse;
import com.cardauth.auth.config.AppProperties;
import com.cardauth.auth.store.AuthLogItem;
import com.cardauth.auth.store.AuthLogRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AuthorizationController {

    public record Body(@NotBlank String cardId, @Positive long amount, String merchant, String mcc) {}

    private final AuthorizationService service;
    private final AuthLogRepository authLog;
    private final String instanceId;

    public AuthorizationController(AuthorizationService service, AuthLogRepository authLog, AppProperties props) {
        this.service = service; this.authLog = authLog; this.instanceId = props.instanceId();
    }

    @PostMapping("/authorizations")
    public ResponseEntity<AuthorizationResponse> authorise(
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Strategy", required = false) String strategy,
            @Valid @RequestBody Body body) {
        AuthorizationResponse r = service.authorise(key, strategy,
                new AuthorizationRequest(body.cardId(), body.amount(), body.merchant(), body.mcc()));
        return ResponseEntity.ok().header("X-Instance", instanceId).body(r);
    }

    @PostMapping("/authorizations/{id}/reverse")
    public Map<String, Object> reverse(@PathVariable String id) {
        if (!service.reverse(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no reversible authorisation " + id);
        return Map.of("authId", id, "reversed", true);
    }

    /** Audit lookup in DynamoDB (newest first). */
    @GetMapping("/cards/{cardId}/authorizations")
    public List<AuthLogItem> history(@PathVariable String cardId, @RequestParam(defaultValue = "20") int limit) {
        return authLog.byCard(cardId, limit);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String badRequest(IllegalArgumentException e) { return e.getMessage(); }
}
