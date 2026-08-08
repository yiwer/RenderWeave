package cn.hbads.renderweave.inference.dashscope;

import cn.hbads.renderweave.inference.provider.ProviderCallException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

final class JdkDashScopeHttpTransport implements DashScopeHttpTransport {
    private final HttpClient client;

    JdkDashScopeHttpTransport() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    JdkDashScopeHttpTransport(HttpClient client) {
        this.client = java.util.Objects.requireNonNull(client, "client");
    }

    @Override
    public DashScopeHttpResponse exchange(
            URI uri,
            Map<String, String> headers,
            byte[] body,
            Duration timeout
    ) {
        var builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(builder::header);
        try {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new DashScopeHttpResponse(
                    response.statusCode(), response.headers().map(), response.body()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProviderCallException(
                    "DASHSCOPE_INTERRUPTED", false, null, Optional.empty(), exception
            );
        } catch (IOException exception) {
            throw new ProviderCallException(
                    "DASHSCOPE_NETWORK_ERROR", true, null, Optional.empty(), exception
            );
        }
    }
}
