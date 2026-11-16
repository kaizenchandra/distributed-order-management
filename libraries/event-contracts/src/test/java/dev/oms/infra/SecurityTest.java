package dev.oms.infra;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SecurityTest.Config.class)
@WebAppConfiguration
class SecurityTest {
    @Autowired
    WebApplicationContext context;
    MockMvc mvc;

    @Test
    void pendingResourceLookupUsesProblem404() throws Exception {
        mvc.perform(get("/api/v1/not-ready").with(jwt())).andExpect(status().isNotFound());
    }

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void anonymousCannotReadOrders() throws Exception {
        mvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void customerCannotMutateFinancialOrAdministrativeResources() throws Exception {
        mvc.perform(get("/api/v1/catalog/quotes").with(jwt())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/payments/test").with(jwt())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/sagas/test").with(jwt())).andExpect(status().isForbidden());
    }

    @Test
    void serviceAuthorityOnlyAllowsParticipantResources() throws Exception {
        var auth = jwt().authorities(new SimpleGrantedAuthority("SCOPE_oms.service"));
        mvc.perform(get("/api/v1/payments/test").with(auth)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/sagas/test").with(auth)).andExpect(status().isForbidden());
    }

    @Test
    void operatorCanInspectSaga() throws Exception {
        mvc.perform(
                        get("/api/v1/admin/sagas/test")
                                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_oms.admin"))))
                .andExpect(status().isOk());
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({Security.class, Errors.class})
    static class Config {
        @Bean
        JwtDecoder decoder() {
            return org.mockito.Mockito.mock(JwtDecoder.class);
        }

        @Bean
        Probe probe() {
            return new Probe();
        }
    }

    @RestController
    static class Probe {
        @GetMapping("/api/v1/not-ready")
        String missing() {
            throw new org.springframework.dao.EmptyResultDataAccessException(1);
        }

        @GetMapping({
                "/api/v1/orders",
                "/api/v1/catalog/quotes",
                "/api/v1/payments/test",
                "/api/v1/admin/sagas/test",
                "/actuator/health"
        })
        String ok() {
            return "ok";
        }
    }
}
