package dev.oms.notificationservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"dev.oms.infra", "dev.oms.notificationservice"})
@EnableScheduling
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    org.springframework.transaction.support.TransactionTemplate tx(
            org.springframework.transaction.PlatformTransactionManager m) {
        return new org.springframework.transaction.support.TransactionTemplate(m);
    }
}
