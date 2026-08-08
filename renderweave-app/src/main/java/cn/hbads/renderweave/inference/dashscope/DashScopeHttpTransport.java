package cn.hbads.renderweave.inference.dashscope;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

@FunctionalInterface
interface DashScopeHttpTransport {
    DashScopeHttpResponse exchange(URI uri, Map<String, String> headers, byte[] body, Duration timeout);
}
