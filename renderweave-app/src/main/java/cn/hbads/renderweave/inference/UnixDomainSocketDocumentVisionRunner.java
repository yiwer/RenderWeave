package cn.hbads.renderweave.inference;

import cn.hbads.renderweave.inference.vision.DocumentVisionException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Transport for the production OCR sidecar: HTTP/1.1 over an AF_UNIX socket. The sidecar has no
 * IP interface; this runner is the only client path and never opens TCP.
 */
final class UnixDomainSocketDocumentVisionRunner
        implements LocalProcessDocumentVisionPreprocessor.ProcessRunner {
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    private final Path socketPath;

    UnixDomainSocketDocumentVisionRunner(Path socketPath) {
        this.socketPath = Objects.requireNonNull(socketPath, "socketPath");
    }

    @Override
    public byte[] execute(
            List<String> command,
            byte[] input,
            Duration timeout,
            Map<String, String> environment
    ) {
        var capability = command.contains("--capability");
        var request = capability ? httpGet("/capability") : httpPost("/ocr", input);
        var timedOut = new java.util.concurrent.atomic.AtomicBoolean();
        try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
                var tasks = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            channel.connect(UnixDomainSocketAddress.of(socketPath));
            var watchdog = tasks.submit(() -> {
                try {
                    Thread.sleep(timeout.toMillis());
                    timedOut.set(true);
                    channel.close();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                    // The channel may already be closed; the deadline flag carries the verdict.
                }
                return null;
            });
            try {
                writeFully(channel, request);
                var response = readResponse(channel);
                return decodeHttpResponse(response);
            } catch (IOException failure) {
                if (timedOut.get()) {
                    throw new DocumentVisionException("DOCUMENT_VISION_TIMEOUT");
                }
                throw new DocumentVisionException("DOCUMENT_VISION_PROCESS_FAILED");
            } finally {
                watchdog.cancel(true);
            }
        } catch (DocumentVisionException known) {
            throw known;
        } catch (java.nio.channels.ClosedByInterruptException interrupted) {
            Thread.currentThread().interrupt();
            throw new DocumentVisionException("DOCUMENT_VISION_INTERRUPTED");
        } catch (IOException failure) {
            throw new DocumentVisionException("DOCUMENT_VISION_PROCESS_FAILED");
        }
    }

    private static byte[] httpGet(String path) {
        return ("GET " + path + " HTTP/1.1\r\n"
                + "Host: ocr-sidecar\r\n"
                + "Accept: application/json\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] httpPost(String path, byte[] body) {
        var header = ("POST " + path + " HTTP/1.1\r\n"
                + "Host: ocr-sidecar\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        var request = new byte[header.length + body.length];
        System.arraycopy(header, 0, request, 0, header.length);
        System.arraycopy(body, 0, request, header.length, body.length);
        return request;
    }

    private static void writeFully(SocketChannel channel, byte[] request) throws IOException {
        var buffer = ByteBuffer.wrap(request);
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) < 0) {
                throw new IOException("OCR sidecar closed the connection during request write");
            }
        }
    }

    private static byte[] readResponse(SocketChannel channel) throws IOException {
        var output = new ByteArrayOutputStream();
        var buffer = ByteBuffer.allocate(8 * 1024);
        while (true) {
            var read = channel.read(buffer);
            if (read < 0) {
                return output.toByteArray();
            }
            if (read == 0) continue;
            if (output.size() + read > MAX_RESPONSE_BYTES) {
                throw new DocumentVisionException("DOCUMENT_VISION_OUTPUT_TOO_LARGE");
            }
            buffer.flip();
            var chunk = new byte[buffer.remaining()];
            buffer.get(chunk);
            output.write(chunk);
            buffer.clear();
        }
    }

    private static byte[] decodeHttpResponse(byte[] raw) {
        var separator = indexOf(raw, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        if (separator < 0) {
            throw new DocumentVisionException("DOCUMENT_VISION_OUTPUT_INVALID");
        }
        var head = new String(raw, 0, separator, StandardCharsets.US_ASCII);
        var lines = head.split("\r\n");
        if (lines.length == 0) {
            throw new DocumentVisionException("DOCUMENT_VISION_OUTPUT_INVALID");
        }
        var statusParts = lines[0].split(" ", 3);
        if (statusParts.length < 2) {
            throw new DocumentVisionException("DOCUMENT_VISION_OUTPUT_INVALID");
        }
        final int status;
        try {
            status = Integer.parseInt(statusParts[1]);
        } catch (NumberFormatException invalid) {
            throw new DocumentVisionException("DOCUMENT_VISION_OUTPUT_INVALID");
        }
        var body = new byte[raw.length - separator - 4];
        System.arraycopy(raw, separator + 4, body, 0, body.length);
        if (status == 200) {
            return body;
        }
        throw new DocumentVisionException(extractErrorCode(body, status));
    }

    private static String extractErrorCode(byte[] body, int status) {
        var text = new String(body, StandardCharsets.UTF_8);
        var marker = "\"errorCode\":\"";
        var start = text.indexOf(marker);
        if (start >= 0) {
            var codeStart = start + marker.length();
            var end = text.indexOf('"', codeStart);
            if (end > codeStart) {
                var code = text.substring(codeStart, end);
                if (code.matches("[A-Z][A-Z0-9_]{2,95}")) {
                    return code;
                }
            }
        }
        return status >= 500 ? "DOCUMENT_VISION_PROCESS_FAILED" : "DOCUMENT_VISION_OUTPUT_INVALID";
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (var index = 0; index <= haystack.length - needle.length; index++) {
            for (var offset = 0; offset < needle.length; offset++) {
                if (haystack[index + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }
}
