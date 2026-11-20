package io.notifyflow.tests;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.notifyflow.api.*;
import io.notifyflow.contracts.*;
import io.notifyflow.messaging.*;
import io.notifyflow.provider.*;
import io.notifyflow.worker.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.*;

abstract class Fixture {
  static PostgreSQLContainer<?> postgres;
  static RabbitMQContainer broker;
  static ConfigurableApplicationContext api, worker, provider;
  static JdbcTemplate adb, wdb, pdb;
  static ObjectMapper json;
  static final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  static AtomicBoolean crash = new AtomicBoolean();

  @Configuration(proxyBeanMethods = false)
  static class CrashConfiguration {
    @Bean
    @Primary
    CrashHook crashOnce() {
      return new CrashHook() {
        @Override
        public void afterProvider(NotificationEvent event) {
          if (event.notification().subject().equals("CRASH") && crash.compareAndSet(true, false))
            throw new IllegalStateException("Injected crash after provider commit");
        }
      };
    }
  }

  @BeforeAll
  static synchronized void initialize() {
    if (api != null) return;
    postgres = new PostgreSQLContainer<>("postgres:17.11-alpine");
    postgres.start();
    var admin = db(postgres.getJdbcUrl());
    for (String name : List.of("api", "worker", "provider"))
      admin.execute("create database " + name);
    broker = new RabbitMQContainer("rabbitmq:4.2.5-management-alpine");
    broker.start();
    provider = start(ProviderApplication.class, "provider", null);
    worker = start(WorkerApplication.class, "worker", CrashConfiguration.class);
    api = start(ApiApplication.class, "api", null);
    adb = api.getBean(JdbcTemplate.class);
    wdb = worker.getBean(JdbcTemplate.class);
    pdb = provider.getBean(JdbcTemplate.class);
    json = api.getBean(ObjectMapper.class);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  api.close();
                  worker.close();
                  provider.close();
                  broker.stop();
                  postgres.stop();
                }));
  }

  static JdbcTemplate db(String url) {
    return new JdbcTemplate(
        new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
  }

  static ConfigurableApplicationContext start(
      Class<?> application, String database, Class<?> extra) {
    List<String> args =
        new ArrayList<>(
            List.of(
                "--server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl().replace("/test", "/" + database),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.flyway.locations=classpath:db/" + database,
                "--spring.rabbitmq.host=" + broker.getHost(),
                "--spring.rabbitmq.port=" + broker.getAmqpPort(),
                "--spring.rabbitmq.username=" + broker.getAdminUsername(),
                "--spring.rabbitmq.password=" + broker.getAdminPassword(),
                "--spring.rabbitmq.listener.simple.auto-startup=false",
                "--notifyflow.retry-base-ms=100",
                "--notifyflow.relay-initial-delay-ms=3600000",
                "--notifyflow.provider-timeout-ms=300",
                "--notifyflow.scenarios-enabled=true",
                "--logging.structured.format.console=",
                "--logging.level.root=WARN"));
    if (provider != null) args.add("--notifyflow.provider-url=http://localhost:" + port(provider));
    if (worker != null) args.add("--notifyflow.worker-url=http://localhost:" + port(worker));
    var builder = new SpringApplicationBuilder(application);
    if (extra != null) builder.sources(extra);
    return builder.run(args.toArray(String[]::new));
  }

  static int port(ConfigurableApplicationContext app) {
    return Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
  }

  @BeforeEach
  void reset() {
    listeners(false);
    crash.set(false);
    var admin =
        new RabbitAdmin(
            api.getBean(org.springframework.amqp.rabbit.connection.ConnectionFactory.class));
    for (String queue :
        List.of(
            Topology.QUEUE,
            Topology.DLQ,
            "notifyflow.delivery.email.retry.1.v1",
            "notifyflow.delivery.email.retry.2.v1",
            "notifyflow.delivery.email.retry.3.v1")) admin.purgeQueue(queue);
    adb.execute("truncate intake_keys, notifications, outbox_events cascade");
    wdb.execute("truncate deliveries,delivery_attempts,outbox_events cascade");
    pdb.execute("truncate provider_requests,provider_effects cascade");
  }

  static void listeners(boolean start) {
    var registry = worker.getBean(RabbitListenerEndpointRegistry.class);
    if (start) registry.start();
    else registry.stop();
  }

  static NotificationRequest request(String subject) {
    return new NotificationRequest("recipient@example.test", "EMAIL", subject, "Message body");
  }

  static HttpResponse<String> post(String key, NotificationRequest request) throws Exception {
    return call(api, "/api/v1/notifications", key, request);
  }

  static HttpResponse<String> call(
      ConfigurableApplicationContext app, String path, String key, Object payload)
      throws Exception {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port(app) + path))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", key)
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  static UUID accept(String subject) throws Exception {
    var result = post(UUID.randomUUID().toString(), request(subject));
    assertThat(result.statusCode()).isEqualTo(202);
    return UUID.fromString(json.readTree(result.body()).get("notificationId").asText());
  }

  static void pump() {
    api.getBean(OutboxRelay.class).poll();
    worker.getBean(OutboxRelay.class).poll();
  }

  static void delivered(UUID id) {
    await()
        .atMost(Duration.ofSeconds(40))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(
            () -> {
              pump();
              assertThat(
                      wdb.queryForObject(
                          "select count(*) from deliveries where notification_id=? and"
                              + " status='DELIVERED'",
                          Integer.class,
                          id))
                  .isEqualTo(1);
            });
  }

  static int count(JdbcTemplate db, String table) {
    return db.queryForObject("select count(*) from " + table, Integer.class);
  }

  static int dlqCount() {
    return new RabbitAdmin(
            api.getBean(org.springframework.amqp.rabbit.connection.ConnectionFactory.class))
        .getQueueInfo(Topology.DLQ)
        .getMessageCount();
  }

  static void dead(UUID id) {
    await()
        .atMost(Duration.ofSeconds(40))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(
            () -> {
              pump();
              assertThat(
                      wdb.queryForObject(
                          "select count(*) from deliveries where notification_id=? and"
                              + " status='DEAD_LETTERED'",
                          Integer.class,
                          id))
                  .isEqualTo(1);
              assertThat(dlqCount()).isEqualTo(1);
            });
  }

  static List<HttpResponse<String>> concurrent(Callable<HttpResponse<String>> action)
      throws Exception {
    try (var executor = Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<Future<HttpResponse<String>>>();
      for (int i = 0; i < 16; i++) tasks.add(executor.submit(action));
      var results = new ArrayList<HttpResponse<String>>();
      for (var task : tasks) results.add(task.get(30, TimeUnit.SECONDS));
      return results;
    }
  }
}
