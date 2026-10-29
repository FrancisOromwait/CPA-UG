package com.estates.sacco.security;

import com.estates.sacco.service.DeploymentSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.config.annotation.ObjectPostProcessor;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;

@Configuration
public class SecurityConfig {
    /**
     * Where the page may load things from: only this site. Styles may be written in the page (the screens set some sizes that way);
     * pictures may also be data and blob addresses (the icon, member photos, PDFs opened in the browser).
     */
    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; "
            + "form-action 'self'; frame-ancestors 'none'";

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, DeploymentSettings settings) throws Exception {
        // In a single-branch installation there is no manager to keep the chart: the branch administrator does
        String[] chartRoles = settings.isSingleBranch() ? new String[] {"MANAGER", "BRANCH_ADMIN"} : new String[] {"MANAGER"};
        http
            .csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Protections the browser applies to every answer (refusals included, hence written straight away):
            // HTTPS only, nothing loaded from other sites, no framing, no address passed on, no camera/microphone/location
            // except the camera for member photos, and no other site's window able to reach this one.
            .headers(h -> h
                .withObjectPostProcessor(new ObjectPostProcessor<HeaderWriterFilter>() {
                    @Override
                    public <O extends HeaderWriterFilter> O postProcess(O filter) {
                        filter.setShouldWriteHeadersEagerly(true);
                        return filter;
                    }
                })
                .httpStrictTransportSecurity(t -> t.includeSubDomains(true).maxAgeInSeconds(31_536_000).requestMatcher(AnyRequestMatcher.INSTANCE))
                .contentSecurityPolicy(c -> c.policyDirectives(CONTENT_SECURITY_POLICY))
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .crossOriginOpenerPolicy(o -> o.policy(CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy.SAME_ORIGIN))
                .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                        "camera=(self), microphone=(), geolocation=(), payment=(), usb=()")))
            // No WWW-Authenticate header: the web app shows its own login form, not the browser popup.
            .httpBasic(b -> b.authenticationEntryPoint((req, res, ex) -> {
                res.setStatus(401);
                res.setContentType("application/json");
                res.getWriter().write(ex instanceof LockedException
                        ? "{\"error\":\"This login is locked after " + LoginGuard.MAX_TRIES + " wrong passwords. Ask the administrator to reset the password"
                          + " (an administrator's own login: ask the support login). The support login itself opens again after 30 minutes.\"}"
                        : "{\"error\":\"Invalid username or password\"}");
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
                // SMS to members: the branch administrator sends; the manager (support) can look at what was sent
                .requestMatchers(HttpMethod.POST, "/api/messages/**").hasRole("BRANCH_ADMIN")
                .requestMatchers("/api/messages/**").hasAnyRole("MANAGER", "BRANCH_ADMIN")
                // a member's login can be reset by staff, and by the support login (the only one that unlocks a locked login)
                .requestMatchers(HttpMethod.POST, "/api/members/*/reset-login").hasAnyRole("MANAGER", "BRANCH_ADMIN", "TREASURER", "CASHIER")
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
