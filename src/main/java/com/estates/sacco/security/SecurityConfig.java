package com.estates.sacco.security;

import com.estates.sacco.service.DeploymentSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, DeploymentSettings settings) throws Exception {
        // In a single-branch installation there is no manager to keep the chart: the branch administrator does
        String[] chartRoles = settings.isSingleBranch() ? new String[] {"MANAGER", "BRANCH_ADMIN"} : new String[] {"MANAGER"};
        http
            .csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // No WWW-Authenticate header: the web app shows its own login form, not the browser popup.
            .httpBasic(b -> b.authenticationEntryPoint((req, res, ex) -> {
                res.setStatus(401);
                res.setContentType("application/json");
                res.getWriter().write("{\"error\":\"Invalid username or password\"}");
            }))
            .authorizeHttpRequests(a -> a
                // The web app itself (HTML/CSS/JS); data still requires login
                .requestMatchers(HttpMethod.GET, "/", "/index.html", "/app.js", "/style.css", "/favicon.ico").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
                // A member's own login: their statement only
                .requestMatchers("/api/my/**").hasRole("MEMBER")
                .requestMatchers(HttpMethod.GET, "/api/me").hasAnyRole("MANAGER", "BRANCH_ADMIN", "TREASURER", "CASHIER", "MEMBER")
                .requestMatchers(HttpMethod.PUT, "/api/me/password").hasAnyRole("MANAGER", "BRANCH_ADMIN", "TREASURER", "CASHIER")
                // Administration of the group: manager only. The chart of accounts is one shared chart.
                .requestMatchers(HttpMethod.POST, "/api/accounts/**").hasAnyRole(chartRoles)
                .requestMatchers(HttpMethod.PUT, "/api/accounts/**").hasAnyRole(chartRoles)
                .requestMatchers(HttpMethod.DELETE, "/api/accounts/**").hasAnyRole(chartRoles)
                .requestMatchers("/api/year-codes/**").hasRole("MANAGER")
                .requestMatchers("/api/users/**").hasAnyRole("MANAGER", "BRANCH_ADMIN")   // a branch admin is limited to their own staff in UserController
                .requestMatchers(HttpMethod.POST, "/api/mobile-money/**").hasAnyRole(chartRoles)
                .requestMatchers(HttpMethod.PUT, "/api/mobile-money/**").hasAnyRole(chartRoles)
                .requestMatchers(HttpMethod.DELETE, "/api/mobile-money/**").hasAnyRole(chartRoles)
                .requestMatchers(HttpMethod.POST, "/api/branches/**").hasRole("MANAGER")
                .requestMatchers(HttpMethod.PUT, "/api/branches/**").hasRole("MANAGER")
                // Posting transactions: branch admins and their staff (manager is read-only oversight)
                .requestMatchers(HttpMethod.POST, "/api/**").hasAnyRole("BRANCH_ADMIN", "TREASURER", "CASHIER")
                .requestMatchers(HttpMethod.PUT, "/api/**").hasAnyRole("BRANCH_ADMIN", "TREASURER", "CASHIER")
                .requestMatchers(HttpMethod.DELETE, "/api/**").hasAnyRole("BRANCH_ADMIN", "TREASURER", "CASHIER")
                // Reads: both roles, narrowed per branch by BranchScope
                .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("MANAGER", "BRANCH_ADMIN", "TREASURER", "CASHIER")
                .anyRequest().denyAll());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
