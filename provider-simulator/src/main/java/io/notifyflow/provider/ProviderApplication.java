package io.notifyflow.provider;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootApplication
public class ProviderApplication {
    public static void main(String[] args) { SpringApplication.run(ProviderApplication.class,args); }
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean TransactionTemplate transactions(PlatformTransactionManager manager) { return new TransactionTemplate(manager); }
}
