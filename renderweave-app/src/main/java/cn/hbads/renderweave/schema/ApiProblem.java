package cn.hbads.renderweave.schema;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiProblem(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String code,
        String traceId,
        List<ApiViolation> violations,
        Long revision
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApiViolation(
            String code,
            String pointer,
            Map<String, Object> args,
            String message
    ) {
    }
}
