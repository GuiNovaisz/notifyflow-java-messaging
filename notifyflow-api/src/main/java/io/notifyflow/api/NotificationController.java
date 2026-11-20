package io.notifyflow.api;

import io.notifyflow.contracts.NotificationRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController @RequestMapping("/api/v1/notifications") @Validated
public class NotificationController {
    private final IntakeService intake;
    private final JdbcTemplate db;
    private final RestClient worker;
    public NotificationController(IntakeService intake,JdbcTemplate db,@Value("${notifyflow.worker-url:http://localhost:8081}") String url) {
        this.intake=intake; this.db=db;
        var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000); factory.setReadTimeout(3000);
        this.worker=RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
    @PostMapping
    ResponseEntity<?> create(@RequestHeader("Idempotency-Key") @NotBlank @Size(max=128) String key,
            @Valid @RequestBody NotificationRequest request) {
        UUID correlation=UUID.randomUUID();
        UUID id=intake.accept(key,request,correlation.toString());
        return ResponseEntity.accepted().location(URI.create("/api/v1/notifications/"+id))
                .header("X-Correlation-ID",correlation.toString()).body(Map.of("notificationId",id,"status","ACCEPTED"));
    }
    @GetMapping("/{id}") Map<String,Object> get(@PathVariable UUID id) {
        var rows=db.queryForList("select id as \"notificationId\",recipient,channel,subject,status,created_at as \"createdAt\",updated_at as \"updatedAt\" from notifications where id=?",id);
        if(rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND,"Notification not found");
        return rows.getFirst();
    }
    @GetMapping("/{id}/deliveries") Object deliveries(@PathVariable UUID id) {
        get(id); return worker.get().uri("/internal/notifications/{id}/deliveries",id).retrieve().body(Object.class);
    }
    @GetMapping("/{id}/attempts") Object attempts(@PathVariable UUID id) {
        get(id); return worker.get().uri("/internal/notifications/{id}/attempts",id).retrieve().body(Object.class);
    }
}
