package dev.backed.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import static dev.backed.config.Roles.CHECKER;
import static dev.backed.config.Roles.CLERK;
import static dev.backed.config.Roles.MAKER;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    public static final String DEMO_PASSWORD = "demo";

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService users(PasswordEncoder encoder) {
        String hash = encoder.encode(DEMO_PASSWORD);
        return new InMemoryUserDetailsManager(
                User.withUsername("clerk").password(hash).roles(CLERK).build(),
                User.withUsername("maker").password(hash).roles(MAKER).build(),
                User.withUsername("checker").password(hash).roles(CHECKER).build());
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/c/**", "/webhooks/**", "/login", "/error",
                                "/css/**", "/js/**", "/img/**", "/favicon.ico", "/actuator/health").permitAll()
                        .requestMatchers("/pos", "/pos/**").hasAnyRole(CLERK, MAKER)
                        .requestMatchers(HttpMethod.POST, "/proposals/*/approve").hasRole(CHECKER)
                        .requestMatchers("/proposals", "/proposals/**").hasAnyRole(MAKER, CHECKER)
                        .requestMatchers("/close", "/close/**", "/topups", "/topups/**", "/cashouts", "/cashouts/**",
                                "/admin", "/admin/**").hasAnyRole(MAKER, CHECKER)
                        .anyRequest().authenticated())
                .formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/", false).permitAll())
                .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/login?logout").permitAll())
                .csrf(csrf -> csrf.ignoringRequestMatchers("/webhooks/**"));
        return http.build();
    }
}
