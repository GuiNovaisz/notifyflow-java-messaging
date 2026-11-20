package io.notifyflow.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.notifyflow.contracts.*;
import io.notifyflow.messaging.Topology;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Service
public class IntakeService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Clock clock;
    public IntakeService(JdbcTemplate db,ObjectMapper json,Clock clock) { this.db=db; this.json=json; this.clock=clock; }
    @Transactional
    public UUID accept(String key, NotificationRequest request, String correlation) {
        String payload = write(request);
        UUID id = UUID.randomUUID();
        // ON CONFLICT waits for concurrent transaction; no check-then-insert race.
        int inserted = db.update("insert into intake_keys (key, payload, notification_id) values (?, ?, ?) on conflict (key) do nothing",key,payload,id);
        if (inserted==0) {
            var existing = db.queryForMap("select * from intake_keys where key=?",key);
            if (!payload.equals(existing.get("payload"))) throw new ResponseStatusException(CONFLICT,"Idempotency key payload conflict");
            return (UUID)existing.get("notification_id");
        }
        Instant now = clock.instant();
        var time=now.atOffset(ZoneOffset.UTC);
        db.update("insert into notifications(id,recipient,channel,subject,body,status,created_at,updated_at) values(?,?,?,?,?,'ACCEPTED',?,?)",id,request.recipient(),request.channel(),request.subject(),request.body(),time,time);
        UUID eventId=UUID.randomUUID();
        var event=new NotificationEvent(eventId,id,NotificationEvent.TYPE,1,now,request,correlation,0);
        db.update("insert into outbox_events(id,event_id,aggregate_id,event_type,event_version,occurred_at,payload,exchange_name,routing_key,category) values(?,?,?,?,1,?,?,?,?, 'INITIAL')",UUID.randomUUID(),eventId,id,NotificationEvent.TYPE,time,write(event),Topology.EXCHANGE,Topology.KEY);
        return id;
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("Invalid contract",e); }
    }
}
