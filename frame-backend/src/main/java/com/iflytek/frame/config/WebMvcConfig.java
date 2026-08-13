package com.iflytek.frame.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Misc WebMvc tweaks. CORS is handled centrally by Spring Security
 * ({@code SecurityConfig#corsConfigurationSource}), so it is NOT redeclared here
 * — declaring it in both places causes duplicate headers.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${frame.upload.dir:./uploads}")
    private String uploadDir;

    /** Forward SPA-style fallback routes to the home page if ever needed. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // intentionally empty — backend is a pure JSON API, no view templates.
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + java.nio.file.Path.of(uploadDir).toAbsolutePath() + "/");
    }
}
