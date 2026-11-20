package io.notifyflow.worker;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootApplication(scanBasePackages = {"io.notifyflow.worker", "io.notifyflow.messaging"})
@EnableScheduling
public class WorkerApplication {
  public static void main(String[] args) {
    SpringApplication.run(WorkerApplication.class, args);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  TransactionTemplate transactions(PlatformTransactionManager manager) {
    return new TransactionTemplate(manager);
  }
}
