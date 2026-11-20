package io.notifyflow.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.notifyflow.contracts.*;
import io.notifyflow.messaging.Topology;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Service
public class DeliveryService {
    private static final Logger log=LoggerFactory.getLogger(DeliveryService.class);
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;
    private final RestClient provider;
    private final long base;
    private final CrashHook crash;
    private final MeterRegistry metrics;
    public DeliveryService(JdbcTemplate db,TransactionTemplate tx,ObjectMapper json,Clock clock,CrashHook crash,MeterRegistry metrics,
            @Value("${notifyflow.provider-url:http://localhost:8082}") String url,
            @Value("${notifyflow.provider-timeout-ms:2000}") int timeout,
            @Value("${notifyflow.retry-base-ms:5000}") long base) {
        this.db=db;this.tx=tx;this.json=json;this.clock=clock;this.base=base;this.crash=crash;this.metrics=metrics;
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(timeout);factory.setReadTimeout(timeout);
        provider=RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
    public void process(NotificationEvent event) {
        // Durable identity first. Concurrent inserts serialize on event_id; processing uses a row lock.
        tx.executeWithoutResult(s -> db.update("insert into deliveries(id,event_id,notification_id,status,attempt_count,updated_at) values(?,?,?,'PENDING',0,?) on conflict(event_id) do nothing",event.eventId(),event.eventId(),event.notificationId(),now()));
        tx.executeWithoutResult(s -> {
            var row=db.queryForMap("select * from deliveries where event_id=? for update",event.eventId());
            int completed=((Number)row.get("attempt_count")).intValue();
            String state=(String)row.get("status");
            if(state.equals("DELIVERED") || state.equals("DEAD_LETTERED") || completed!=event.generation()) {
                metrics.counter("notifyflow.consumer.duplicate").increment();
                log.info("event=duplicate eventId={} notificationId={} deliveryId={} correlationId={}",event.eventId(),event.notificationId(),event.eventId(),event.correlationId());return;
            }
            var started=now();int status=0;String category="NONE";
            var timer=io.micrometer.core.instrument.Timer.start(metrics);
            try {
                status=provider.post().uri("/deliveries").header("Idempotency-Key",event.eventId().toString())
                        .header("X-Correlation-ID",event.correlationId()).body(new ProviderRequest(event.notificationId(),event.notification()))
                        .exchange((request,response)->response.getStatusCode().value());
            } catch(org.springframework.web.client.RestClientException failure) { category="TIMEOUT_OR_CONNECTION"; }
            finally { timer.stop(metrics.timer("notifyflow.processing.duration")); }
            if(status>=200 && status<300) crash.afterProvider(event);
            boolean success=status>=200 && status<300;
            boolean retry=!success && (status==0 || RetryPolicy.transientStatus(status)) && completed+1<RetryPolicy.MAX_ATTEMPTS;
            if(!success && status!=0) category=RetryPolicy.transientStatus(status)?"PROVIDER_TRANSIENT":"PROVIDER_PERMANENT";
            int attempt=completed+1;
            String nextState=success?"DELIVERED":retry?"RETRYING":"DEAD_LETTERED";
            var next=retry?clock.instant().plusMillis(RetryPolicy.delay(attempt,base)).atOffset(ZoneOffset.UTC):null;
            db.update("insert into delivery_attempts(delivery_id,attempt_number,started_at,finished_at,outcome,failure_category,provider_status,next_retry_at) values(?,?,?,?,?,?,?,?)",event.eventId(),attempt,started,now(),nextState,category,status==0?null:status,next);
            db.update("update deliveries set status=?,attempt_count=?,updated_at=? where event_id=?",nextState,attempt,now(),event.eventId());
            if(!success) {
                String payload=write(retry?event.nextGeneration():event);
                db.update("insert into outbox_events(id,event_id,aggregate_id,event_type,event_version,occurred_at,payload,exchange_name,routing_key,category) values(?,?,?,?,1,?,?,?,?,?)",UUID.randomUUID(),event.eventId(),event.notificationId(),NotificationEvent.TYPE,now(),payload,retry?Topology.RETRY:Topology.DEAD,retry?"retry."+attempt:"dead",category);
                metrics.counter(retry?"notifyflow.retry.count":"notifyflow.dlq.count").increment();
            }
            metrics.counter(success?"notifyflow.delivery.success":"notifyflow.delivery.failure").increment();
            metrics.counter("notifyflow.consumer.processed").increment();
            log.info("event=delivery_completed notificationId={} eventId={} deliveryId={} correlationId={} attempt={} outcome={} category={}",event.notificationId(),event.eventId(),event.eventId(),event.correlationId(),attempt,nextState,category);
        });
    }
    private java.time.OffsetDateTime now() { return clock.instant().atOffset(ZoneOffset.UTC); }
    private String write(Object object) { try { return json.writeValueAsString(object); } catch(Exception e) { throw new IllegalStateException(e); } }
}
