package com.demo.WechatBot.config;

import com.demo.WechatBot.service.AdminUserDetailsService;
import com.demo.WechatBot.service.AdminUserPrincipal;
import com.demo.WechatBot.service.AdminUserStatusFilter;
import com.demo.WechatBot.service.AdminUserStore;
import com.demo.WechatBot.service.LoginAttemptFilter;
import com.demo.WechatBot.service.LoginAttemptService;
import com.demo.WechatBot.service.SecurityResponseWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider(
            AdminUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            DaoAuthenticationProvider authenticationProvider,
            LoginAttemptService attemptService,
            LoginAttemptFilter loginAttemptFilter,
            AdminUserStatusFilter adminUserStatusFilter,
            SecurityResponseWriter responseWriter,
            AdminUserStore userStore) throws Exception {

        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName("_csrf");

        http
                .authenticationProvider(authenticationProvider)
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfRequestHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login.html", "/auth.js", "/error").permitAll()
                        .requestMatchers("/api/auth/login", "/api/auth/csrf", "/api/auth/register").permitAll()
                        .requestMatchers("/api/admin/users/**").hasRole("ADMIN")
                        .requestMatchers("/admin.html").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login.html")
                        .loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) -> {
                            attemptService.recordSuccess(
                                    request.getParameter("username"), request.getRemoteAddr());
                            if (authentication.getPrincipal() instanceof AdminUserPrincipal principal) {
                                userStore.touchLogin(principal.getId());
                            }
                            responseWriter.writeOk(response);
                        })
                        .failureHandler((request, response, exception) -> {
                            boolean locked = attemptService.recordFailure(
                                    request.getParameter("username"), request.getRemoteAddr());
                            if (locked) {
                                responseWriter.writeError(response, 429, "登录尝试过于频繁，请稍后再试");
                            } else {
                                responseWriter.writeError(response, 401, "用户名或密码错误");
                            }
                        })
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(204)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            if (request.getRequestURI().startsWith("/api/")) {
                                responseWriter.writeError(response, 401, "请先登录");
                            } else {
                                response.sendRedirect("/login.html");
                            }
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            if (request.getRequestURI().startsWith("/api/")) {
                                responseWriter.writeError(response, 403, "请求已过期或权限不足");
                            } else {
                                response.sendError(403);
                            }
                        }))
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.migrateSession()))
                .headers(headers -> headers.frameOptions(frame -> frame.deny()))
                .addFilterBefore(loginAttemptFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(adminUserStatusFilter, SecurityContextHolderFilter.class);

        return http.build();
    }
}
