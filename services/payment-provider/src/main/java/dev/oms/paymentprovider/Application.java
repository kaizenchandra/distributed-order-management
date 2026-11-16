package dev.oms.paymentprovider;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.oms.infra.Json;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    Json json(ObjectMapper mapper) {
        return new Json(mapper);
    }

    @Bean
    SecurityFilterChain filter(HttpSecurity h) throws Exception {
        return h.csrf(c -> c.disable())
                .authorizeHttpRequests(
                        a ->
                                a.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR)
                                        .permitAll()
                                        .requestMatchers("/actuator/health/**", "/internal/**")
                                        .permitAll()
                                        .anyRequest()
                                        .denyAll())
                .build();
    }
}
