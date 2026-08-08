package cn.hbads.renderweave.inference.dashscope;

import cn.hbads.renderweave.inference.provider.ProviderCallException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

final class JdkDashScopeHttpTransport implements DashScopeHttpTransport {
    private static final int MAX_RESPONSE_BYTES = 3 * 1024 * 1024;
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
            var response = client.send(
                    builder.build(), ignored -> new BoundedBodySubscriber(MAX_RESPONSE_BYTES)
            );
            if (response.body().tooLarge()) {
                throw new ProviderCallException(
                        "DASHSCOPE_RESPONSE_TOO_LARGE", false, response.statusCode(),
                        Optional.empty(), null
                );
            }
            return new DashScopeHttpResponse(
                    response.statusCode(), response.headers().map(), response.body().bytes()
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

    private record BoundedBody(byte[] bytes, boolean tooLarge) { }

    private static final class BoundedBodySubscriber
            implements HttpResponse.BodySubscriber<BoundedBody> {
        private final int maximumBytes;
        private final ByteArrayOutputStream output;
        private final CompletableFuture<BoundedBody> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private BoundedBodySubscriber(int maximumBytes) {
            this.maximumBytes = maximumBytes;
            this.output = new ByteArrayOutputStream(Math.min(maximumBytes, 64 * 1024));
        }

        @Override
        public CompletionStage<BoundedBody> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (this.subscription != null) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            for (var buffer : buffers) {
                if ((long) output.size() + buffer.remaining() > maximumBytes) {
                    subscription.cancel();
                    body.complete(new BoundedBody(new byte[0], true));
                    return;
                }
                var chunk = new byte[Math.min(buffer.remaining(), 8 * 1024)];
                while (buffer.hasRemaining()) {
                    var length = Math.min(buffer.remaining(), chunk.length);
                    buffer.get(chunk, 0, length);
                    output.write(chunk, 0, length);
                }
            }
        }

        @Override
        public void onError(Throwable failure) {
            body.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            body.complete(new BoundedBody(output.toByteArray(), false));
        }
    }
}
