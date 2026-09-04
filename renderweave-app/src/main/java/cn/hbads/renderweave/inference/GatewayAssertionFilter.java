package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.admission.GatewayAssertionAuthority;
import cn.hbads.renderweave.inference.admission.GatewayAssertionProblem;
import cn.hbads.renderweave.inference.admission.GatewayAssertionRequest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.UUID;

final class GatewayAssertionFilter extends OncePerRequestFilter {
    static final String ASSERTION_HEADER = "X-RenderWeave-Gateway-Assertion";
    static final String IDENTITY_ATTRIBUTE = GatewayAssertionFilter.class.getName() + ".identity";
    private final GatewayAssertionAuthority authority;
    private final ClientCertificateGate clientCertificates;
    private final boolean clientCertificateRequired;
    private final ObjectMapper json;

    GatewayAssertionFilter(
            GatewayAssertionAuthority authority,
            ClientCertificateGate clientCertificates,
            boolean clientCertificateRequired,
            ObjectMapper json
    ) {
        this.authority = authority;
        this.clientCertificates = clientCertificates;
        this.clientCertificateRequired = clientCertificateRequired;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        if (clientCertificateRequired && !clientCertificates.permits(request)) {
            writeProblem(response, 403, "GATEWAY_MTLS_IDENTITY_INVALID",
                    "The gateway mTLS identity is invalid.");
            return;
        }
        try {
            var assertion = singleHeader(request, ASSERTION_HEADER);
            var idempotencyKey = singleHeader(request, "Idempotency-Key");
            var identity = authority.authenticate(assertion, new GatewayAssertionRequest(
                    request.getMethod(), request.getRequestURI(), idempotencyKey
            ));
            request.setAttribute(IDENTITY_ATTRIBUTE, identity);
            filterChain.doFilter(request, response);
        } catch (IllegalArgumentException invalidRequestFacts) {
            writeProblem(response, 401, "GATEWAY_ASSERTION_REQUEST_INVALID",
                    "The gateway request binding is invalid.");
        } catch (GatewayAssertionProblem problem) {
            var unavailable = problem.code().equals("TIME_AUTHORITY_UNAVAILABLE")
                    || problem.code().endsWith("_UNAVAILABLE");
            writeProblem(response, unavailable ? 503 : 401, problem.code(), problem.getMessage());
        }
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        var values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) return null;
        var value = values.nextElement();
        if (values.hasMoreElements()) {
            throw new GatewayAssertionProblem(
                    "GATEWAY_ASSERTION_HEADER_AMBIGUOUS", "A security header is ambiguous."
            );
        }
        return value;
    }

    private void writeProblem(
            HttpServletResponse response,
            int status,
            String code,
            String detail
    ) throws IOException {
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "https://renderweave.local/problems/" + code.toLowerCase().replace('_', '-'));
        body.put("title", "Gateway request rejected");
        body.put("status", status);
        body.put("detail", detail);
        body.put("code", code);
        body.put("traceId", UUID.randomUUID().toString());
        json.writeValue(response.getOutputStream(), body);
    }
}
