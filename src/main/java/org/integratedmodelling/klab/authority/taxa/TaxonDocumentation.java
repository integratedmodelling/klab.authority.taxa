package org.integratedmodelling.klab.authority.taxa;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import org.integratedmodelling.klab.api.services.runtime.Notification;

/** Builds durable, content-addressed Markdown and retains upstream media URLs. */
final class TaxonDocumentation {
  static final String MARKDOWN = "text/markdown";

  record Result(Map<String, URL> urls, List<Notification> notifications) {
    Result {
      urls = Map.copyOf(urls);
      notifications = List.copyOf(notifications);
    }
  }

  static Result build(
      ChecklistBankClient client,
      String endpoint,
      int dataset,
      Duration timeout,
      JsonNode datasetMetadata,
      JsonNode usage) {
    var urls = new LinkedHashMap<String, URL>();
    var notifications = new ArrayList<Notification>();
    String id = usage.path("id").asText();
    String encoded = java.net.URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
    String api = endpoint + "/dataset/" + dataset;
    String page = "https://www.checklistbank.org/dataset/" + dataset + "/taxon/" + encoded;
    urls.put("application/json", url(api + "/nameusage/" + encoded));
    urls.put("text/html", url(page));

    var markdown =
        new StringBuilder("# ")
            .append(
                escape(
                    usage.path("label").asText(usage.path("name").path("scientificName").asText())))
            .append("\n\n");
    fields(
        markdown,
        usage.path("name"),
        "scientificName",
        "authorship",
        "rank",
        "code",
        "nomStatus",
        "publishedInYear",
        "publishedInPage",
        "etymology",
        "nomenclaturalNote",
        "remarks");
    fields(
        markdown,
        usage,
        "id",
        "datasetKey",
        "status",
        "parentId",
        "accordingTo",
        "remarks",
        "extinct",
        "environments",
        "temporalRangeStart",
        "temporalRangeEnd",
        "identifier",
        "referenceIds",
        "scrutinizer",
        "scrutinizerDate");
    markdown.append("\n[ChecklistBank taxon](<").append(page).append(">)\n");
    links(markdown, usage, "link");
    links(markdown, usage.path("name"), "link", "publishedInPageLink");
    markdown.append("\n## Dataset\n\n");
    fields(
        markdown,
        datasetMetadata,
        "key",
        "title",
        "alias",
        "version",
        "issued",
        "description",
        "doi",
        "license",
        "creator",
        "editor",
        "publisher",
        "citation");
    links(markdown, datasetMetadata, "url");

    String treatment = api + "/taxon/" + encoded + "/treatment?format=MARKDOWN";
    try {
      var resource = client.treatment(URI.create(treatment), timeout);
      if (resource != null && resource.body().length > 0) {
        String type = resource.mediaType();
        if (MARKDOWN.equals(type)) {
          markdown
              .append("\n## Treatment\n\n")
              .append(new String(resource.body(), StandardCharsets.UTF_8))
              .append('\n');
          markdown.append("\n[Source treatment](<").append(treatment).append(">)\n");
        } else if (validType(type)) {
          urls.putIfAbsent(type, url(treatment));
          markdown
              .append("\n[Treatment (")
              .append(escape(type))
              .append(")](<")
              .append(treatment)
              .append(">)\n");
        }
      }
    } catch (RuntimeException e) {
      notifications.add(
          Notification.warning("TAXA treatment documentation unavailable: " + e.getMessage()));
    }
    try {
      var media = client.media(URI.create(api + "/taxon/" + encoded + "/media"), timeout);
      if (!media.isEmpty()) markdown.append("\n## Media\n\n");
      for (var item : media) {
        URL source = webUrl(item.path("url").asText());
        if (source == null) continue;
        String type =
            item.path("format").asText("").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        // IMAGE/VIDEO/AUDIO are categories, not MIME formats. Do not guess from a filename.
        if (validType(type)) urls.putIfAbsent(type, source);
        markdown
            .append("- [")
            .append(escape(item.path("title").asText("Media")))
            .append("](<")
            .append(source.toExternalForm())
            .append(">)\n");
        fields(
            markdown,
            item,
            "format",
            "type",
            "captured",
            "capturedBy",
            "license",
            "remarks",
            "referenceId");
        links(markdown, item, "link", "thumbnail");
      }
    } catch (RuntimeException e) {
      notifications.add(
          Notification.warning("TAXA media documentation unavailable: " + e.getMessage()));
    }
    urls.put(MARKDOWN, materialize(markdown.toString()));
    return new Result(urls, notifications);
  }

  private static void fields(StringBuilder markdown, JsonNode node, String... fields) {
    for (String field : fields) {
      var value = node.path(field);
      if (value.isMissingNode()
          || value.isNull()
          || (value.isTextual() && value.asText().isBlank())) continue;
      String text = value.isValueNode() ? value.asText() : value.toString();
      markdown.append("- **").append(field).append("**: ").append(escape(text)).append('\n');
    }
  }

  private static void links(StringBuilder markdown, JsonNode node, String... fields) {
    for (String field : fields) {
      URL link = webUrl(node.path(field).asText());
      if (link != null)
        markdown
            .append("\n[")
            .append(field)
            .append("](<")
            .append(link.toExternalForm())
            .append(">)\n");
    }
  }

  private static boolean validType(String type) {
    return type.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+") && !type.contains("*");
  }

  private static URL webUrl(String value) {
    try {
      var uri = URI.create(value);
      if (uri.getHost() == null
          || uri.getUserInfo() != null
          || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
          || value.contains("<")
          || value.contains(">")) return null;
      return uri.toURL();
    } catch (IllegalArgumentException | IOException e) {
      return null;
    }
  }

  private static URL url(String value) {
    try {
      return URI.create(value).toURL();
    } catch (IOException e) {
      throw new IllegalStateException("Invalid documentation URL", e);
    }
  }

  /** Files outlive bridge/cache eviction; cached identities must keep working after restart. */
  private static synchronized URL materialize(String markdown) {
    try {
      byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      Path directory =
          Path.of(System.getProperty("java.io.tmpdir"), "klab.authority.taxa", "documentation");
      Files.createDirectories(directory);
      Path destination = directory.resolve(hash + ".md");
      if (!Files.exists(destination)) {
        Path staging = Files.createTempFile(directory, "markdown-", ".tmp");
        try {
          Files.write(staging, bytes);
          try {
            Files.move(staging, destination);
          } catch (java.nio.file.FileAlreadyExistsException ignored) {
            /* Another process wrote the same content. */
          }
        } finally {
          Files.deleteIfExists(staging);
        }
      }
      return destination.toUri().toURL();
    } catch (IOException | NoSuchAlgorithmException e) {
      throw new IllegalStateException("Cannot materialize taxon Markdown documentation", e);
    }
  }

  private static String escape(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\\", "\\\\")
        .replace("*", "\\*")
        .replace("_", "\\_")
        .replace("[", "\\[")
        .replace("]", "\\]")
        .replace("`", "\\`")
        .replace("\r", " ")
        .replace("\n", " ");
  }
}
