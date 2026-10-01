package org.integratedmodelling.klab.authority.taxa;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.integratedmodelling.klab.api.services.Authority.ConfigurationRequest;
import org.integratedmodelling.klab.api.services.runtime.Notification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaxaAuthorityTest {
  private HttpServer server;
  private TaxaAuthority authority;
  private final Map<String, String> responses = new HashMap<>();
  private final List<String> requests = new ArrayList<>();
  private final Map<String, String> contentTypes = new HashMap<>();
  private String endpoint;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          requests.add(exchange.getRequestURI().toString());
          var payload = responses.getOrDefault(path, "{}").getBytes(StandardCharsets.UTF_8);
          exchange
              .getResponseHeaders()
              .set("Content-Type", contentTypes.getOrDefault(path, "application/json"));
          exchange.sendResponseHeaders(responses.containsKey(path) ? 200 : 404, payload.length);
          try (var body = exchange.getResponseBody()) {
            body.write(payload);
          }
        });
    server.start();
    endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    authority = new TaxaAuthority();
    responses.put(
        "/dataset/123",
        "{\"key\":123,\"sourceKey\":3,\"origin\":\"xrelease\",\"version\":\"fixture XR\"}");
    responses.put("/dataset/123/nameusage/A1", usage("A1", "G1", "accepted", "species"));
    responses.put("/dataset/123/nameusage/G1", usage("G1", "N", "accepted", "genus"));
    responses.put("/dataset/123/nameusage/N", usage("N", null, "accepted", "kingdom"));
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private ConfigurationRequest request(String name, String root) {
    return new ConfigurationRequest(
        "worldview",
        name,
        root,
        Map.of("urn", TaxaAuthority.URN, "datasetKey", 123, "endpoint", endpoint));
  }

  private String configure() {
    return authority.configure(request("TAXA", "biology:Taxon"));
  }

  private static String usage(String id, String parent, String status, String rank) {
    return "{\"datasetKey\":123,\"id\":\""
        + id
        + "\",\"status\":\""
        + status
        + "\",\"label\":\"Scientific "
        + id
        + " Author\",\"name\":{\"scientificName\":\"Scientific "
        + id
        + "\",\"rank\":\""
        + rank
        + "\"}"
        + (parent == null ? "" : ",\"parentId\":\"" + parent + "\"")
        + "}";
  }

  private static void error(org.integratedmodelling.klab.api.services.Authority.Identity identity) {
    assertTrue(
        identity.getNotifications().stream()
            .anyMatch(n -> n.getLevel() == Notification.Level.Error));
  }

  @Test
  void resolvesHierarchyWithRootOnlyAtTopAndCachesLookups() {
    var config = configure();
    var leaf = authority.resolveIdentity(config, "A1");
    assertEquals(List.of("G1"), leaf.getParentIds());
    assertNull(leaf.getBaseIdentity());
    assertEquals("TAXA:A1", leaf.getLocator());
    assertEquals("taxon_123_4131", leaf.getConceptName());
    assertEquals("Scientific A1 Author", leaf.getLabel());
    assertEquals("biology:Taxon", authority.resolveIdentity(config, "N").getBaseIdentity());
    assertTrue(authority.resolveIdentity(config, "N").getParentIds().isEmpty());
    assertTrue(authority.resolveIdentity(config, "G1").getNotifications().isEmpty());
    assertEquals(1, requests.stream().filter(r -> r.endsWith("/nameusage/A1")).count());
    assertEquals(1, requests.stream().filter(r -> r.endsWith("/nameusage/G1")).count());
    assertEquals(1, requests.stream().filter(r -> r.endsWith("/nameusage/N")).count());
  }

  @Test
  void canonicalizesSynonymsWithoutAddingASubclass() {
    responses.put("/dataset/123/nameusage/S1", usage("S1", "A1", "synonym", "species"));
    var config = configure();
    var synonym = authority.resolveIdentity(config, "S1");
    assertEquals("A1", synonym.getId());
    assertEquals(
        authority.resolveIdentity(config, "A1").getConceptName(), synonym.getConceptName());
    assertEquals(List.of("G1"), synonym.getParentIds());
  }

  @Test
  void isolatesBridgeRootsAndNamesAndReleasesRankViews() {
    String first = configure();
    String second = authority.configure(request("OTHER", "other:Root"));
    assertNotEquals(first, second);
    assertEquals("other:Root", authority.resolveIdentity(second, "N").getBaseIdentity());
    assertEquals("OTHER:N", authority.resolveIdentity(second, "N").getLocator());
    var species = authority.subAuthority("species");
    assertEquals(List.of("N"), species.resolveIdentity(first, "G1").getParentIds());
    species.releaseConfiguration(first);
    assertThrows(IllegalArgumentException.class, () -> authority.resolveIdentity(first, "A1"));
    assertTrue(authority.resolveIdentity(second, "A1").getNotifications().isEmpty());
  }

  @Test
  void rejectsMutableDeletedAndForeignReleasesAndInvalidParameters() {
    for (String extra :
        List.of(
            "\"origin\":\"project\"",
            "\"origin\":\"release\"",
            "\"origin\":\"xrelease\",\"deleted\":\"today\"",
            "\"origin\":\"xrelease\",\"private\":true")) {
      responses.put("/dataset/123", "{\"key\":123,\"sourceKey\":3," + extra + "}");
      assertThrows(IllegalArgumentException.class, this::configure);
    }
    var missing =
        new ConfigurationRequest("wv", "TAXA", "bio:Root", Map.of("urn", TaxaAuthority.URN));
    assertThrows(IllegalArgumentException.class, () -> authority.configure(missing));
    var typo =
        new ConfigurationRequest(
            "wv",
            "TAXA",
            "bio:Root",
            Map.of("urn", TaxaAuthority.URN, "datasetKey", 123, "catalog", "species"));
    assertThrows(IllegalArgumentException.class, () -> authority.configure(typo));
    assertThrows(IllegalArgumentException.class, () -> authority.subAuthority("PHLYUM"));
  }

  @Test
  void rejectsCyclesMissingParentsAndAmbiguousOrUnplacedUsages() {
    responses.put("/dataset/123/nameusage/G1", usage("G1", "A1", "accepted", "genus"));
    error(authority.resolveIdentity(configure(), "A1"));
    responses.put("/dataset/123/nameusage/G1", usage("G1", "MISSING", "accepted", "genus"));
    error(authority.resolveIdentity(configure(), "A1"));
    for (String status : List.of("bare name", "ambiguous synonym", "misapplied")) {
      responses.put("/dataset/123/nameusage/A1", usage("A1", "N", status, "species"));
      error(authority.resolveIdentity(configure(), "A1"));
    }
    error(authority.resolveIdentity(configure(), "Scientific name"));
  }

  @Test
  void scientificAndVernacularSearchUsesRankOnlyAsAFilter() {
    responses.put(
        "/dataset/123/nameusage/search",
        "{\"total\":3,\"result\":[{\"usage\":"
            + usage("A1", "G1", "accepted", "species")
            + "},{\"usage\":"
            + usage("G1", "N", "accepted", "genus")
            + "},{\"usage\":"
            + usage("A1", "G1", "accepted", "species")
            + "}]}");
    var config = configure();
    var results = authority.subAuthority("SPECIES").search("common name & synonym", null, config);
    assertEquals(1, results.size());
    assertEquals("A1", results.getFirst().getId());
    String search =
        requests.stream().filter(r -> r.contains("/nameusage/search?")).findFirst().orElseThrow();
    assertTrue(search.contains("content=SCIENTIFIC_NAME&content=VERNACULAR_NAME"));
    assertTrue(search.contains("minRank=SPECIES&maxRank=SPECIES"));
    assertTrue(search.contains("common+name+%26+synonym"));
    assertEquals(
        List.of("G1"),
        authority.resolveIdentity(config, results.getFirst().getId()).getParentIds());
    responses.remove("/dataset/123/nameusage/search");
    assertThrows(IllegalStateException.class, () -> authority.search("query", null, config));
  }

  @Test
  void reconciliationRequiresSafeMatchTypesEvenWhenMatchIsTrue() {
    var config = configure();
    for (var type : List.of("ambiguous", "higherrank", "none", "unsupported")) {
      responses.put(
          "/dataset/123/match/nameusage",
          "{\"match\":true,\"type\":\"" + type + "\",\"usage\":{\"id\":\"A1\"}}");
      error(
          authority.reconcile(config, Map.of("scientificName", "Oenanthe", "kingdom", "Plantae")));
    }
    responses.put(
        "/dataset/123/match/nameusage",
        "{\"match\":true,\"type\":\"exact\",\"usage\":{\"id\":\"A1\"}}");
    assertEquals(
        "A1", authority.reconcile(config, Map.of("scientificName", "Scientific A1")).getId());
    assertThrows(
        IllegalArgumentException.class,
        () -> authority.reconcile(config, Map.of("scientificName", "x", "datasetKey", "other")));
  }

  @Test
  void rejectsMalformedAndWrongDatasetResponsesWithoutCachingFailures() {
    var config = configure();
    responses.put("/dataset/123/nameusage/A1", "not json");
    error(authority.resolveIdentity(config, "A1"));
    responses.put(
        "/dataset/123/nameusage/A1",
        usage("A1", "G1", "accepted", "subspecies").replace("123", "999"));
    error(authority.resolveIdentity(config, "A1"));
    responses.put("/dataset/123/nameusage/A1", usage("A1", "G1", "accepted", "subspecies"));
    assertTrue(authority.resolveIdentity(config, "A1").getNotifications().isEmpty());
    assertTrue(authority.resolveIdentity(config, "A1").getDescription().contains("subspecies"));
  }

  @Test
  void boundsResponsesAndEnforcesADeadlineIncludingTheBody() throws Exception {
    responses.put("/oversize", "x".repeat(4 * 1024 * 1024 + 1));
    var client = new ChecklistBankClient();
    assertThrows(
        IllegalStateException.class,
        () ->
            client.get(
                java.net.URI.create(endpoint + "/oversize"), java.time.Duration.ofSeconds(5)));
    server.createContext(
        "/slow",
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          try (var body = exchange.getResponseBody()) {
            body.write('{');
            body.flush();
            try {
              Thread.sleep(2000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            body.write('}');
          } catch (java.io.IOException ignored) {
            /* Client cancels the timed-out request. */
          }
        });
    long start = System.nanoTime();
    var failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                client.get(
                    java.net.URI.create(endpoint + "/slow"), java.time.Duration.ofSeconds(1)));
    assertTrue(failure.getMessage().contains("timed out"));
    assertTrue(java.time.Duration.ofNanos(System.nanoTime() - start).toMillis() < 1800);
  }

  private static String markdown(
      org.integratedmodelling.klab.api.services.Authority.Identity identity) throws Exception {
    try (var input = identity.getDocumentation().get("text/markdown").openStream()) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void generatesReadableImmutableDocumentationForResolutionSearchAndSynonyms() throws Exception {
    String config = configure();
    var identity = authority.resolveIdentity(config, "A1");
    String document = markdown(identity);
    assertTrue(document.contains("Scientific A1 Author"));
    assertTrue(document.contains("fixture XR"));
    assertTrue(document.contains("**parentId**: G1"));
    assertEquals(
        endpoint + "/dataset/123/nameusage/A1",
        identity.getDocumentation().get("application/json").toExternalForm());
    assertThrows(UnsupportedOperationException.class, () -> identity.getDocumentation().clear());
    responses.put("/dataset/123/nameusage/S1", usage("S1", "A1", "synonym", "species"));
    assertEquals(
        identity.getDocumentation(), authority.resolveIdentity(config, "S1").getDocumentation());
    responses.put(
        "/dataset/123/nameusage/search",
        "{\"result\":[{\"usage\":" + usage("A1", "G1", "accepted", "species") + "}]}");
    assertEquals(
        identity.getDocumentation(),
        authority.search("Scientific", null, config).getFirst().getDocumentation());
    assertEquals(1, requests.stream().filter(r -> r.endsWith("/taxon/A1/media")).count());
    assertTrue(authority.resolveIdentity(config, "MISSING").getDocumentation().isEmpty());
  }

  @Test
  void addsTreatmentMetadataAndEveryMediaLinkUsingExplicitMimeTypes() throws Exception {
    String path = "/dataset/123/taxon/A1/";
    responses.put(path + "treatment", "A **Markdown** treatment with café.");
    contentTypes.put(path + "treatment", "text/markdown; charset=UTF-8");
    responses.put(
        path + "media",
        """
        [{"url":"https://example.org/one.jpg","format":"image/jpeg","title":"First",
          "capturedBy":"Photographer","license":"CC_BY"},
         {"url":"https://example.org/two.jpg","format":"image/jpeg","title":"Second"},
         {"url":"https://example.org/paper.pdf","format":"application/pdf"},
         {"url":"https://example.org/unknown.jpg","type":"IMAGE"},
         {"url":"javascript:alert(1)","format":"text/html"}]
        """);
    responses.put(
        "/dataset/123/nameusage/A1",
        usage("A1", "G1", "accepted", "species")
            .replace(
                "\"status\":",
                "\"remarks\":\"Note <script> & detail\",\"extinct\":false,\"status\":"));
    var identity = authority.resolveIdentity(configure(), "A1");
    String document = markdown(identity);
    assertTrue(document.contains("A **Markdown** treatment with café."));
    assertTrue(document.contains("two.jpg"));
    assertTrue(document.contains("Photographer"));
    assertTrue(document.contains("CC\\_BY"));
    assertTrue(document.contains("&lt;script&gt; &amp; detail"));
    assertTrue(document.contains("**extinct**: false"));
    assertFalse(document.contains("javascript:"));
    assertEquals(
        "https://example.org/one.jpg",
        identity.getDocumentation().get("image/jpeg").toExternalForm());
    assertEquals(
        "https://example.org/paper.pdf",
        identity.getDocumentation().get("application/pdf").toExternalForm());
  }

  @Test
  void optionalMalformedMediaDoesNotPreventResolutionAndIsRetried() throws Exception {
    responses.put("/dataset/123/taxon/A1/media", "{}");
    String config = configure();
    var identity = authority.resolveIdentity(config, "A1");
    assertEquals("A1", identity.getId());
    assertFalse(identity.getNotifications().isEmpty());
    assertFalse(
        identity.getNotifications().stream()
            .anyMatch(n -> n.getLevel() == Notification.Level.Error));
    assertFalse(markdown(identity).isBlank());
    responses.put("/dataset/123/taxon/A1/media", "[]");
    assertTrue(authority.resolveIdentity(config, "A1").getNotifications().isEmpty());
  }

  @Test
  void preservesNonMarkdownTreatmentWithItsReportedContentType() throws Exception {
    String path = "/dataset/123/taxon/A1/treatment";
    responses.put(path, "<treatment>Details</treatment>");
    contentTypes.put(path, "application/xml; charset=UTF-8");
    var identity = authority.resolveIdentity(configure(), "A1");
    assertEquals(
        endpoint + path + "?format=MARKDOWN",
        identity.getDocumentation().get("application/xml").toExternalForm());
    assertTrue(markdown(identity).contains("Treatment (application/xml)"));
    assertFalse(markdown(identity).contains("<treatment>"));
  }
}
