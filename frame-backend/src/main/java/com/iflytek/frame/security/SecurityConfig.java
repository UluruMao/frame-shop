package com.iflytek.frame.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.frame.common.Result;
import com.iflytek.frame.common.ResultCode;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security wiring for stateless JWT auth.
 *
 * <ul>
 *   <li>Stateless sessions (no server-side session) — enables horizontal scaling.</li>
 *   <li>{@link JwtAuthenticationFilter} runs before the username/password filter.</li>
 *   <li>Public paths: Swagger/OpenAPI + login/register. Everything else requires auth.</li>
 *   <li>CORS configured for the Vite dev origin (see {@code frame.cors.*}).</li>
 * </ul>
 */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(RateLimitProperties.class)
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CorsProperties corsProperties;
    private final ApplicationContext applicationContext;
    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties rateLimitProperties;

    /** Per-user token-bucket on {@code POST /checkout}. Runs AFTER the JWT filter
     *  so the {@code SecurityContextHolder} already has the authenticated principal. */
    @Bean
    public RateLimitFilter rateLimitFilter() {
        return new RateLimitFilter(redisTemplate, rateLimitProperties, applicationContext);
    }

    /** Resolved lazily so the security config doesn't hard-require an ObjectMapper bean at startup. */
    private ObjectMapper objectMapper() {
        try {
            return applicationContext.getBean(ObjectMapper.class);
        } catch (org.springframework.beans.factory.NoSuchBeanDefinitionException e) {
            return new ObjectMapper();
        }
    }

    /** BCrypt for demo login password hashing — even though login is a stub here, wire the real thing. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)            // stateless JWT, no CSRF token needed
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/auth/login",
                                "/auth/register",
                                "/auth/sms/send",
                                "/auth/password/forgot",
                                "/captcha"
                        ).permitAll()
                        // Swagger / OpenAPI
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/swagger-resources/**",
                                "/webjars/**"
                        ).permitAll()
                        // public product reads (browsing — no login)
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/products", "/products/{id}", "/products/categories").permitAll()
                        // public product images (uploaded static files — anonymous browsing)
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/uploads/**").permitAll()
                        // admin-only product mutations + sale management
                        .requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/products", "/products/{id}/image", "/admin/sales/{productId}")
                                .hasRole("ADMIN")
                        .requestMatchers(org.springframework.http.HttpMethod.PUT,
                                "/products/{id}").hasRole("ADMIN")
                        .requestMatchers(org.springframework.http.HttpMethod.DELETE,
                                "/products/{id}").hasRole("ADMIN")
                        // admin-only operation-log audit trail
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/admin/operation-logs").hasRole("ADMIN")
                        // admin-only order lifecycle + refund adjudication (Phase C)
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/admin/orders", "/admin/refunds", "/admin/stats").hasRole("ADMIN")
                        .requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/admin/orders/{id}/ship", "/admin/orders/{id}/deliver",
                                "/admin/refunds/{orderId}/approve", "/admin/refunds/{orderId}/deny")
                                .hasRole("ADMIN")
                        // WebSocket handshake endpoint — auth happens in the ChannelInterceptor,
                        // not Spring Security's filter chain, so permit the HTTP upgrade.
                        .requestMatchers("/ws-chat/**").permitAll()
                        // user chat REST endpoints (UC011)
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/chat/sessions").hasRole("USER")
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/chat/sessions/**").hasRole("USER")
                        // admin chat REST endpoints (UC011)
                        .requestMatchers("/admin/chat/**").hasRole("ADMIN")
                        // everything else requires authentication
                        .anyRequest().authenticated())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((req, resp, ex) ->
                                writeError(resp, HttpServletResponse.SC_UNAUTHORIZED,
                                        ResultCode.UNAUTHORIZED, "authentication required"))
                        .accessDeniedHandler((req, resp, ex) ->
                                writeError(resp, HttpServletResponse.SC_FORBIDDEN,
                                        ResultCode.FORBIDDEN, "access denied")))
                // JWT filter runs before the username/password filter (standard stateless setup).
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // Rate limiter runs AFTER the JWT filter so the SecurityContextHolder
                // already holds the authenticated principal (needed for the userId bucket key).
                // Note: must register the JWT filter first, otherwise its order is unknown
                // and addFilterAfter cannot resolve the anchor.
                .addFilterAfter(rateLimitFilter(), JwtAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(List.of(corsProperties.getAllowedOrigins().split(",")));
        cfg.setAllowedMethods(List.of(corsProperties.getAllowedMethods().split(",")));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setAllowCredentials(corsProperties.isAllowCredentials());
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    private void writeError(HttpServletResponse resp, int status,
                            ResultCode code, String message) throws java.io.IOException {
        resp.setStatus(status);
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write(objectMapper().writeValueAsString(Result.fail(code, message)));
    }
}
