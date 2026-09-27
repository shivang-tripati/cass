package com.shivang.obd.security.config;

import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.api.response.RequestIdFilter;
import com.shivang.obd.common.exception.ForbiddenException;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class RestAuthErrorHandling {

    private static final String GENERIC_UNAUTHORIZED = "Authentication required.";

    @Bean
    public AuthenticationEntryPoint bearerAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, authException) ->
            writeProblem(response, objectMapper, 401,
                CommonErrorCode.UNAUTHORIZED, GENERIC_UNAUTHORIZED);
    }

    @Bean
    public AccessDeniedHandler restAccessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, accessDeniedException) ->
            writeProblem(response, objectMapper, 403,
                CommonErrorCode.FORBIDDEN, CommonErrorCode.FORBIDDEN.defaultMessage());
    }

    private static void writeProblem(
        HttpServletResponse response,
        ObjectMapper objectMapper,
        int status,
        CommonErrorCode errorCode,
        String detail
    ) {
        try {
            response.setStatus(status);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            Map<String, Object> body = Map.of(
                "title", errorCode.defaultMessage(),
                "status", status,
                "detail", detail,
                "code", errorCode.code(),
                "requestId", String.valueOf(MDC.get(RequestIdFilter.MDC_REQUEST_ID_KEY)),
                "timestamp", Instant.now().toString()
            );
            objectMapper.writeValue(response.getWriter(), body);
        } catch (Exception ignored) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }
}
