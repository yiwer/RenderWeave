package cn.hbads.renderweave.inference.dashscope;

import java.util.List;
import java.util.Map;

record DashScopeHttpResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {
    DashScopeHttpResponse {
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("statusCode must be a valid HTTP status");
        }
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
