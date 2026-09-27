package com.shivang.obd.security.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI/Swagger foundation: bearer JWT security scheme referenced by the
 * {@code bearerAuth} requirement on authenticated endpoints.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI obdOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("OBD Platform API")
                .description("Multi-tenant OBD CPaaS platform. "
                    + "All responses follow the common ApiResponse envelope; "
                    + "errors use RFC 7807 ProblemDetail.")
                .version("v1"))
            .components(new Components().addSecuritySchemes("bearerAuth",
                new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")))
            .addSecurityItem(new io.swagger.v3.oas.models.security.SecurityRequirement().addList("bearerAuth"));
    }
}
