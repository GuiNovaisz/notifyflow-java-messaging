package io.notifyflow.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.notifyflow.contracts.ProviderRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@RestController @Validated
public class ProviderController {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;
    private final boolean scenarios;
    public ProviderController(JdbcTemplate db,TransactionTemplate tx,ObjectMapper json,Clock clock,@Value("${notifyflow.scenarios-enabled:false}") boolean scenarios) {
        this.db=db;this.tx=tx;this.json=json;this.clock=clock;this.scenarios=scenarios;
    }
    @PostMapping("/deliveries")
    ResponseEntity<?> send(@RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,@Valid @RequestBody ProviderRequest request) throws Exception {
        String payload=json.writeValueAsString(request);
        var result=tx.execute(s -> {
            db.update("insert into provider_requests(key,payload,calls) values(?,?,0) on conflict(key) do nothing",key,payload);
            var row=db.queryForMap("select * from provider_requests where key=? for update",key);
            if(!payload.equals(row.get("payload"))) return ResponseEntity.status(409).body(Map.of("error","IDEMPOTENCY_CONFLICT"));
            int calls=((Number)row.get("calls")).intValue()+1;
            db.update("update provider_requests set calls=? where key=?",calls,key);
            var effects=db.queryForList("select id from provider_effects where key=?",key);
            if(!effects.isEmpty()) return ResponseEntity.ok(Map.of("deliveryId",effects.getFirst().get("id")));
            String scenario=scenarios?request.notification().subject():"SUCCESS";
            if(scenario.equals("PERMANENT")) return ResponseEntity.badRequest().body(Map.of("error","PERMANENT"));
            if(scenario.equals("ALWAYS_FAIL") || scenario.equals("FAIL_TWICE") && calls<=2)
                return ResponseEntity.status(500).body(Map.of("error","TRANSIENT"));
            UUID id=UUID.randomUUID();
            db.update("insert into provider_effects(id,key,created_at) values(?,?,?)",id,key,clock.instant().atOffset(ZoneOffset.UTC));
            return ResponseEntity.ok(Map.of("deliveryId",id));
        });
        // Effect is committed before response delay: timeout is an ambiguous result, not proof of failure.
        if(scenarios && (request.notification().subject().equals("TIMEOUT") || request.notification().subject().equals("SLOW")))
            java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofSeconds(3).toNanos());
        return result;
    }
    @ExceptionHandler(Exception.class) ResponseEntity<?> invalid(Exception e) {
        int status=e instanceof org.springframework.dao.DataAccessException?503:400;
        return ResponseEntity.status(status).body(org.springframework.http.ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(status),"Provider request unavailable or invalid"));
    }
}
