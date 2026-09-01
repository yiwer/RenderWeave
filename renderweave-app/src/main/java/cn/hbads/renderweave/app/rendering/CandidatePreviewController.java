package cn.hbads.renderweave.app.rendering;

import cn.hbads.renderweave.template.api.DesignInputExpressionCapacityAuthority;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

/** Loopback-only Candidate Preview delivery. This is intentionally not a public API. */
@RestController
@RequestMapping("/internal/candidate-preview/templates")
@ConditionalOnProperty(
        name = "renderweave.template.candidate-preview.enabled",
        havingValue = "true")
final class CandidatePreviewController {

    static final String CANDIDATE_STATUS_HEADER = "RenderWeave-Candidate-Status";
    static final String NOT_CERTIFIED = "NOT_CERTIFIED";

    private static final Pattern IPV4_LOOPBACK = Pattern.compile(
            "127(?:\\.(?:0|[1-9][0-9]?|1[0-9]{2}|2[0-4][0-9]|25[0-5])){3}");

    private final RenderingController delivery;

    CandidatePreviewController(
            CandidatePreviewApplication candidate,
            DesignInputExpressionCapacityAuthority capacityAuthority
    ) {
        this.delivery = new RenderingController(candidate::render, capacityAuthority);
    }

    @PostMapping("/{templateId}")
    ResponseEntity<?> preview(
            @PathVariable String templateId,
            @RequestParam MultiValueMap<String, String> query,
            HttpServletRequest request
    ) {
        if (!isLoopback(request.getRemoteAddr())) {
            return ResponseEntity.notFound().build();
        }
        return disclose(delivery.authoritativePreview(templateId, query, request));
    }

    private static ResponseEntity<?> disclose(ResponseEntity<?> response) {
        var headers = new HttpHeaders();
        headers.putAll(response.getHeaders());
        headers.set(CANDIDATE_STATUS_HEADER, NOT_CERTIFIED);
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
    }

    private static boolean isLoopback(String remoteAddress) {
        return remoteAddress != null
                && (IPV4_LOOPBACK.matcher(remoteAddress).matches()
                || "::1".equals(remoteAddress)
                || "0:0:0:0:0:0:0:1".equals(remoteAddress));
    }
}
