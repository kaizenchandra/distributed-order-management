package dev.oms.infra;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class Security {
    static org.springframework.security.authentication.AbstractAuthenticationToken authenticate(
            org.springframework.security.oauth2.jwt.Jwt jwt) {
        if (jwt.getSubject() == null || jwt.getSubject().isBlank())
            throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException(
                    new org.springframework.security.oauth2.core.OAuth2Error("invalid_token"),
                    "Subject required");
        return new org.springframework.security.oauth2.server.resource.authentication
                .JwtAuthenticationConverter()
                .convert(jwt);
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity h) throws Exception {
        return h.csrf(c -> c.disable())
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers("/actuator/health/**")
                                        .permitAll()
                                        .requestMatchers("/actuator/**", "/api/v1/admin/**")
                                        .hasAuthority("SCOPE_oms.admin")
                                        .requestMatchers(
                                                "/api/v1/inventory/**", "/api/v1/payments/**", "/api/v1/fulfillments/**")
                                        .hasAuthority("SCOPE_oms.service")
                                        .anyRequest()
                                        .authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(Security::authenticate)))
                .build();
    }
}
