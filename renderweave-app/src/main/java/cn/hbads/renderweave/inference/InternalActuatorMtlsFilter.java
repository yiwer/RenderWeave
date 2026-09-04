package cn.hbads.renderweave.inference;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

final class InternalActuatorMtlsFilter extends OncePerRequestFilter {
    private final ClientCertificateGate certificates;
    private final ObjectMapper json;

    InternalActuatorMtlsFilter(ClientCertificateGate certificates, ObjectMapper json) {
        this.certificates = certificates;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        if (!certificates.permits(request)) {
            response.setStatus(403);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            json.writeValue(response.getOutputStream(), Map.of(
                    "type", "https://renderweave.local/problems/actuator-mtls-identity-invalid",
                    "title", "Internal operation rejected",
                    "status", 403,
                    "detail", "An exact operations mTLS identity is required.",
                    "code", "ACTUATOR_MTLS_IDENTITY_INVALID"
            ));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
