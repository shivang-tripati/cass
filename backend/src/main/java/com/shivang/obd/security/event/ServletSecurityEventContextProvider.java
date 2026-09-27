package com.shivang.obd.security.event;

import com.shivang.obd.common.api.response.RequestIdFilter;
import com.shivang.obd.security.web.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class ServletSecurityEventContextProvider implements SecurityEventRequestContextProvider {

    private final ClientIpResolver clientIpResolver;

    public ServletSecurityEventContextProvider(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public Optional<RequestMetadata> current() {
        if (!(RequestContextHolder.getRequestAttributes()
            instanceof ServletRequestAttributes attributes)) {
            return Optional.empty();
        }
        HttpServletRequest request = attributes.getRequest();
        return Optional.of(new RequestMetadata(
            MDC.get(RequestIdFilter.MDC_REQUEST_ID_KEY),
            truncate(clientIpResolver.resolve(request), 64),
            truncate(request.getHeader("User-Agent"), 512)));
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
