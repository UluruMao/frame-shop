package com.iflytek.frame;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 5 — config externalization (distributed deployment).
 *
 * <p>The DB host/port and Redis host/port must come from environment variables
 * ({@code FRAME_DB_HOST}, {@code FRAME_DB_PORT}, {@@code FRAME_DB_NAME},
 * {@code FRAME_REDIS_HOST}, {@code FRAME_REDIS_PORT}) with defaults that
 * preserve single-instance local behavior. This test proves the
 * {@code application.yml} placeholders resolve those env vars: when the env
 * vars are set, the resolved {@code spring.datasource.url} /
 * {@code spring.data.redis.host} / {@code spring.data.redis.port} reflect them.
 *
 * <p>This is a focused property-resolution test (not a full {@code @SpringBootTest}):
 * it loads {@code application.yml} as a property source, layers the env vars on
 * top, and asserts the resolved values. It deliberately does NOT start a Spring
 * context — so no DB or Redis connection is attempted. This keeps the test fast
 * and independent of infrastructure while still proving the YAML now contains
 * {@code ${FRAME_DB_HOST:localhost}} (etc.) rather than a hardcoded
 * {@code localhost}. Before the externalization the URL was hardcoded
 * {@code jdbc:mariadb://localhost:3306/...}, so these assertions failed (the
 * TDD red step). After externalization the placeholders resolve from the env
 * vars and the assertions pass.
 *
 * <p>The {@code application-prod.yml} profile uses the identical placeholder
 * syntax (verified by the same machinery against {@code application-prod.yml}),
 * so a prod instance can be pointed at a shared DB/Redis host by setting
 * {@code FRAME_DB_HOST} etc. in its environment.
 */
class DistributedConfigExternalizationTest {

    /**
     * Loads a YAML config file from the classpath into a resolved
     * {@link StandardEnvironment}, with the given env vars layered on top
     * (mimicking how Spring Boot resolves {@code ${FRAME_DB_HOST:localhost}}
     * against the OS environment / {@code -D} properties).
     */
    private static StandardEnvironment resolvedEnv(String classpathYaml, Properties envVars) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(classpathYaml));
        Properties yamlProps = yaml.getObject();

        StandardEnvironment env = new StandardEnvironment();
        // envVars must win over the YAML — add them as a higher-priority source.
        env.getPropertySources().addFirst(new PropertiesPropertySource("envVars", envVars));
        env.getPropertySources().addLast(new PropertiesPropertySource("yaml", yamlProps));
        return env;
    }

    // ---- application.yml (dev profile) ----

    @Test
    void devYaml_datasourceUrl_resolvesFrameDbHostFromEnv() {
        Properties env = new Properties();
        env.setProperty("FRAME_DB_HOST", "myhost");
        env.setProperty("FRAME_DB_PORT", "13306");
        env.setProperty("FRAME_DB_NAME", "myframe_db");

        String url = resolvedEnv("application.yml", env)
                .getProperty("spring.datasource.url");

        assertThat(url)
                .as("application.yml spring.datasource.url must resolve FRAME_DB_HOST/PORT/NAME")
                .contains("myhost")
                .contains(":13306/")
                .contains("/myframe_db")
                // Keep the rest of the JDBC params intact.
                .contains("useUnicode=true")
                .contains("characterEncoding=utf-8")
                .contains("useSSL=false")
                .contains("serverTimezone=Asia/Shanghai");
    }

    @Test
    void devYaml_redisHostPort_resolveFromEnv() {
        Properties env = new Properties();
        env.setProperty("FRAME_REDIS_HOST", "myredis");
        env.setProperty("FRAME_REDIS_PORT", "16379");

        StandardEnvironment resolved = resolvedEnv("application.yml", env);
        assertThat(resolved.getProperty("spring.data.redis.host"))
                .as("application.yml spring.data.redis.host must resolve FRAME_REDIS_HOST")
                .isEqualTo("myredis");
        assertThat(resolved.getProperty("spring.data.redis.port"))
                .as("application.yml spring.data.redis.port must resolve FRAME_REDIS_PORT")
                .isEqualTo("16379");
    }

    @Test
    void devYaml_defaultsPreserveLocalSingleInstanceBehavior() {
        // No env vars set → defaults must restore the original localhost:3306/frame_db
        // and localhost:6379 so a local run with no env behaves exactly as before.
        StandardEnvironment resolved = resolvedEnv("application.yml", new Properties());
        assertThat(resolved.getProperty("spring.datasource.url"))
                .as("default spring.datasource.url must point at localhost:3306/frame_db")
                .contains("//localhost:3306/frame_db");
        assertThat(resolved.getProperty("spring.data.redis.host"))
                .as("default spring.data.redis.host must be localhost")
                .isEqualTo("localhost");
        assertThat(resolved.getProperty("spring.data.redis.port"))
                .as("default spring.data.redis.port must be 6379")
                .isEqualTo("6379");
    }

    // ---- application-prod.yml (prod profile) ----

    @Test
    void prodYaml_datasourceUrl_resolvesFrameDbHostFromEnv() {
        Properties env = new Properties();
        env.setProperty("FRAME_DB_HOST", "myhost");
        env.setProperty("FRAME_DB_PORT", "13306");
        env.setProperty("FRAME_DB_NAME", "myframe_db");

        String url = resolvedEnv("application-prod.yml", env)
                .getProperty("spring.datasource.url");

        assertThat(url)
                .as("application-prod.yml spring.datasource.url must resolve FRAME_DB_HOST/PORT/NAME")
                .contains("myhost")
                .contains(":13306/")
                .contains("/myframe_db")
                .contains("serverTimezone=Asia/Shanghai");
    }

    @Test
    void prodYaml_redisHostPort_resolveFromEnv() {
        Properties env = new Properties();
        env.setProperty("FRAME_REDIS_HOST", "myredis");
        env.setProperty("FRAME_REDIS_PORT", "16379");

        StandardEnvironment resolved = resolvedEnv("application-prod.yml", env);
        assertThat(resolved.getProperty("spring.data.redis.host"))
                .as("application-prod.yml spring.data.redis.host must resolve FRAME_REDIS_HOST")
                .isEqualTo("myredis");
        assertThat(resolved.getProperty("spring.data.redis.port"))
                .as("application-prod.yml spring.data.redis.port must resolve FRAME_REDIS_PORT")
                .isEqualTo("16379");
    }

    @Test
    void prodYaml_defaultsPreserveLocalSingleInstanceBehavior() {
        StandardEnvironment resolved = resolvedEnv("application-prod.yml", new Properties());
        assertThat(resolved.getProperty("spring.datasource.url"))
                .as("default prod spring.datasource.url must point at localhost:3306/frame_db")
                .contains("//localhost:3306/frame_db");
        assertThat(resolved.getProperty("spring.data.redis.host"))
                .as("default prod spring.data.redis.host must be localhost")
                .isEqualTo("localhost");
        assertThat(resolved.getProperty("spring.data.redis.port"))
                .as("default prod spring.data.redis.port must be 6379")
                .isEqualTo("6379");
    }
}
