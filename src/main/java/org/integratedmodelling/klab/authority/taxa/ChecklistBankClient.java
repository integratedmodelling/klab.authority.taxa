package org.integratedmodelling.klab.authority.taxa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow.Subscription;

/** Anonymous, bounded read-only ChecklistBank transport. */
final class ChecklistBankClient {
  private static final int MAX_BYTES = 4 * 1024 * 1024;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final ObjectMapper mapper = new ObjectMapper();

  JsonNode get(URI uri, Duration timeout) {
    var response = read(uri, timeout, "application/json", false);
    try {
      var result = mapper.readTree(response.body());
      if (result == null || !result.isObject())
        throw new IllegalStateException("Invalid ChecklistBank JSON object");
      return result;
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Invalid ChecklistBank JSON at " + uri, e);
    }
  }

  /** Media is an array, unlike the object-valued lookup and search endpoints. */
  JsonNode media(URI uri, Duration timeout) {
    var response = read(uri, timeout, "application/json", true);
    if (response == null) return mapper.createArrayNode();
    try {
      var result = mapper.readTree(response.body());
      if (result == null || !result.isArray())
        throw new IllegalStateException("Invalid ChecklistBank media array");
      return result;
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Invalid ChecklistBank media JSON", e);
    }
  }

  record Resource(byte[] body, String mediaType) {}

  Resource treatment(URI uri, Duration timeout) {
    var response = read(uri, timeout, "text/markdown", true);
    if (response == null) return null;
    String type = response.headers().firstValue("Content-Type").orElse("")
        .split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
    return new Resource(response.body(), type);
  }

  private HttpResponse<byte[]> read(URI uri, Duration timeout, String accept, boolean optional) {
    var request =
        HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Accept", accept)
            .header(
                "User-Agent",
                "klab.authority.taxa/1.0 (https://github.com/integratedmodelling/klab.authority.taxa)")
            .GET()
            .build();
    var pending = client.sendAsync(request, info -> new BoundedBody());
    try {
      // This deadline covers the body as well as headers and connection establishment.
      var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (optional && (response.statusCode() == 404 || response.statusCode() == 204)) return null;
      if (response.statusCode() != 200) {
        throw new IllegalStateException(
            "ChecklistBank HTTP " + response.statusCode() + " at " + uri);
      }
      return response;
    } catch (InterruptedException e) {
      pending.cancel(true);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("ChecklistBank request interrupted", e);
    } catch (TimeoutException e) {
      pending.cancel(true);
      throw new IllegalStateException("ChecklistBank request timed out at " + uri, e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("ChecklistBank request failed at " + uri, e);
    }
  }

  private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Subscription subscription;

    @Override
    public CompletionStage<byte[]> getBody() {
      return result;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      for (var buffer : buffers) {
        if (buffer.remaining() > MAX_BYTES - bytes.size()) {
          subscription.cancel();
          result.completeExceptionally(
              new IllegalStateException("ChecklistBank response exceeds 4 MiB"));
          return;
        }
        var chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable error) {
      result.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }
}
