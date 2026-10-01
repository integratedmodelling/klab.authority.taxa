package org.integratedmodelling.klab.authority.taxa;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.integratedmodelling.klab.api.collections.Pair;
import org.integratedmodelling.klab.api.knowledge.Codelist;
import org.integratedmodelling.klab.api.services.Authority;
import org.integratedmodelling.klab.api.services.runtime.Notification;

/** Taxa from one explicitly pinned Catalogue of Life Extended Release per worldview bridge. */
@org.integratedmodelling.klab.api.services.reasoner.Authority(
    urn = TaxaAuthority.URN,
    version = "1.0.0",
    embeddable = true,
    searchable = true,
    subAuthorities = {
      "DOMAIN",
      "KINGDOM",
      "PHYLUM",
      "CLASS",
      "ORDER",
      "FAMILY",
      "TRIBE",
      "GENUS",
      "SPECIES",
      "SUBSPECIES",
      "VARIETY",
      "FORM"
    })
public class TaxaAuthority implements Authority {
  public static final String URN = "klab.authority.taxa";
  private static final Set<String> PARAMETERS =
      Set.of("urn", "datasetKey", "endpoint", "timeoutSeconds", "searchLimit", "cacheSize");
  private static final Set<String> MATCH_HINTS =
      Set.of(
          "scientificName",
          "authorship",
          "code",
          "rank",
          "superkingdom",
          "kingdom",
          "subkingdom",
          "superphylum",
          "phylum",
          "subphylum",
          "superclass",
          "class",
          "subclass",
          "superorder",
          "order",
          "suborder",
          "superfamily",
          "family",
          "subfamily",
          "tribe",
          "subtribe",
          "genus",
          "subgenus",
          "section",
          "species");
  private final ChecklistBankClient client;
  private final Map<String, Bridge> bridges;
  private final Map<String, Authority> views;
  private final String rank;

  public TaxaAuthority() {
    this(new ChecklistBankClient(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), null);
  }

  private TaxaAuthority(
      ChecklistBankClient client,
      Map<String, Bridge> bridges,
      Map<String, Authority> views,
      String rank) {
    this.client = client;
    this.bridges = bridges;
    this.views = views;
    this.rank = rank;
  }

  @Override
  public String getUrn() {
    return URN;
  }

  /** Pinned taxon identities are immutable; query indexes may change independently. */
  @Override
  public CachePolicy getCachePolicy() {
    return new CachePolicy("col-xr-2", Long.MAX_VALUE, 86400, 86400);
  }

  @Override
  public String configure(ConfigurationRequest request) {
    var parameters = request.parameters();
    if (!URN.equals(parameters.get("urn")))
      throw new IllegalArgumentException("Wrong taxa provider URN");
    for (var key : parameters.keySet()) {
      if (!PARAMETERS.contains(key))
        throw new IllegalArgumentException("Unknown taxa parameter: " + key);
    }
    int dataset = integer(parameters, "datasetKey", null, 1, Integer.MAX_VALUE);
    int seconds = integer(parameters, "timeoutSeconds", 20, 1, 120);
    int limit = integer(parameters, "searchLimit", 25, 1, 100);
    int cache = integer(parameters, "cacheSize", 5000, 1, 100000);
    var endpoint = endpoint(parameters.getOrDefault("endpoint", "https://api.checklistbank.org"));
    var timeout = Duration.ofSeconds(seconds);
    var metadata = client.get(URI.create(endpoint + "/dataset/" + dataset), timeout);
    if (metadata.path("key").asInt(-1) != dataset
        || metadata.path("sourceKey").asInt(-1) != 3
        || !"xrelease".equals(metadata.path("origin").asText().toLowerCase(Locale.ROOT))
        || metadata.hasNonNull("deleted")
        || metadata.path("private").asBoolean(false)) {
      throw new IllegalArgumentException(
          "datasetKey must name an available public COL Extended Release from project 3");
    }
    var id = UUID.randomUUID().toString();
    bridges.put(
        id,
        new Bridge(
            request,
            dataset,
            endpoint,
            timeout,
            limit,
            cache,
            metadata));
    return id;
  }

  @Override
  public void releaseConfiguration(String id) {
    if (id != null) bridges.remove(id);
  }

  @Override
  public Identity resolveIdentity(String configurationId, String identityId) {
    var bridge = bridge(configurationId);
    try {
      String id = code(identityId);
      synchronized (bridge) {
        var accepted = accepted(bridge, id, new HashSet<>());
        validateHierarchy(bridge, accepted);
        return identity(bridge, accepted, 1);
      }
    } catch (RuntimeException e) {
      return failure(bridge, identityId, e.getMessage());
    }
  }

  /** Explicit scientific-name reconciliation. Ambiguous and higher-rank matches are rejected. */
  @Override
  public Identity reconcile(String configurationId, Map<String, String> hints) {
    var bridge = bridge(configurationId);
    Objects.requireNonNull(hints, "Reconciliation hints are required");
    if (hints.get("scientificName") == null || hints.get("scientificName").isBlank()) {
      throw new IllegalArgumentException("scientificName is required for reconciliation");
    }
    StringBuilder query = new StringBuilder();
    hints.forEach(
        (key, value) -> {
          if (!MATCH_HINTS.contains(key) || value == null || value.isBlank()) {
            throw new IllegalArgumentException("Invalid reconciliation hint: " + key);
          }
          query.append(query.isEmpty() ? "?" : "&").append(key).append('=').append(encode(value));
        });
    try {
      var match =
          client.get(
              URI.create(
                  bridge.endpoint + "/dataset/" + bridge.dataset + "/match/nameusage" + query),
              bridge.timeout);
      var type = match.path("type").asText().toLowerCase(Locale.ROOT);
      // The API may return match=true and an arbitrary usage even for AMBIGUOUS.
      if (!match.path("match").asBoolean(false)
          || !Set.of("exact", "variant", "canonical").contains(type)) {
        return failure(
            bridge,
            hints.get("scientificName"),
            "Reconciliation requires an unambiguous taxon; match type: " + type);
      }
      return resolveIdentity(configurationId, required(match.path("usage"), "id"));
    } catch (RuntimeException e) {
      return failure(bridge, hints.get("scientificName"), e.getMessage());
    }
  }

  @Override
  public List<Identity> search(String query, String subAuthority, String configurationId) {
    var bridge = bridge(configurationId);
    var filter =
        normalizedRank(subAuthority == null || subAuthority.isBlank() ? rank : subAuthority);
    if (rank != null && filter != null && !rank.equals(filter)) {
      throw new IllegalArgumentException("Conflicting rank filters");
    }
    if (query == null || query.isBlank()) return List.of();
    String uri =
        bridge.endpoint
            + "/dataset/"
            + bridge.dataset
            + "/nameusage/search?q="
            + encode(query.trim())
            + "&content=SCIENTIFIC_NAME&content=VERNACULAR_NAME&sortBy=RELEVANCE&limit="
            + bridge.limit;
    if (filter != null) uri += "&minRank=" + filter + "&maxRank=" + filter;
    var response = client.get(URI.create(uri), bridge.timeout);
    if (!response.path("result").isArray()) {
      if (response.path("total").asLong(-1) == 0) return List.of();
      throw new IllegalStateException("ChecklistBank search omitted its result array");
    }
    var identities = new LinkedHashMap<String, Identity>();
    int index = 0;
    synchronized (bridge) {
      for (var result : response.path("result")) {
        var usage = result.path("usage");
        index++;
        if (index > bridge.limit) break;
        // Bare/unplaced names have no taxon hierarchy and cannot be semantic identities.
        if (!acceptedStatus(usage) && !synonymStatus(usage)) continue;
        if (filter != null
            && !filter.equals(usage.path("name").path("rank").asText().toUpperCase(Locale.ROOT)))
          continue;
        // Search hits omit some taxon metadata. Load the canonical full usage for documentation.
        var accepted = accepted(bridge, required(usage, "id"), new HashSet<>());
        if (filter != null
            && !filter.equals(accepted.path("name").path("rank").asText().toUpperCase(Locale.ROOT)))
          continue;
        var candidate = identity(bridge, accepted, 1f / index);
        identities.putIfAbsent(candidate.getId(), candidate);
      }
    }
    return List.copyOf(identities.values());
  }

  @Override
  public Authority subAuthority(String catalog) {
    var filter = normalizedRank(catalog);
    if (filter == null) return this;
    return views.computeIfAbsent(filter, value -> new TaxaAuthority(client, bridges, views, value));
  }

  @Override
  public Map<String, Codelist> getCodelists() {
    return Map.of();
  }

  @Override
  public Capabilities getCapabilities() {
    return new Capabilities() {
      @Override
      public String getDescription() {
        return "Taxa in a pinned Catalogue of Life Extended Release; scientific and vernacular name search";
      }

      @Override
      public boolean isSearchable() {
        return true;
      }

      @Override
      public boolean isFuzzy() {
        return false;
      }

      @Override
      public boolean isReconciliationSupported() {
        return true;
      }

      @Override
      public boolean areSubAuthoritiesSearchFilters() {
        return true;
      }

      @Override
      public List<Pair<String, String>> getSubAuthorities() {
        var result = new ArrayList<Pair<String, String>>();
        result.add(Pair.of("", "All ranks"));
        TaxaRanks.ALL.forEach(
            value -> result.add(Pair.of(value, "Search rank: " + value.toLowerCase(Locale.ROOT))));
        return List.copyOf(result);
      }

      @Override
      public List<String> getDocumentationFormats() {
        return List.of("text/markdown", "text/html", "application/json");
      }

      @Override
      public String getWorldview() {
        return null;
      }
    };
  }

  private JsonNode usage(Bridge bridge, String id) {
    var cached = bridge.cache.get(id);
    if (cached != null) return cached;
    var response =
        client.get(
            URI.create(
                bridge.endpoint + "/dataset/" + bridge.dataset + "/nameusage/" + encode(code(id))),
            bridge.timeout);
    if (!id.equals(required(response, "id"))
        || response.path("datasetKey").asInt(-1) != bridge.dataset) {
      throw new IllegalStateException("ChecklistBank returned a different taxon or dataset");
    }
    bridge.cache.put(id, response);
    return response;
  }

  private JsonNode accepted(Bridge bridge, String id, Set<String> seen) {
    for (int depth = 0; depth < 256; depth++) {
      if (!seen.add(id)) throw new IllegalStateException("Cyclic synonym mapping at " + id);
      var usage = usage(bridge, id);
      if (acceptedStatus(usage)) return usage;
      if (!synonymStatus(usage))
        throw new IllegalStateException("Name usage is not a placed taxon: " + id);
      id = code(required(usage, "parentId"));
    }
    throw new IllegalStateException("Synonym chain exceeds 256 links");
  }

  private void validateHierarchy(Bridge bridge, JsonNode start) {
    Set<String> seen = new HashSet<>();
    var current = start;
    for (int depth = 0; depth < 256; depth++) {
      String id = required(current, "id");
      if (!seen.add(id)) throw new IllegalStateException("Cyclic taxon parent hierarchy at " + id);
      String parent = current.path("parentId").asText("");
      if (parent.isBlank()) return;
      current = usage(bridge, code(parent));
      if (!acceptedStatus(current))
        throw new IllegalStateException("Taxon parent is not accepted: " + parent);
    }
    throw new IllegalStateException("Taxon hierarchy exceeds 256 links");
  }

  private Identity identity(Bridge bridge, JsonNode usage, float score) {
    String id = code(required(usage, "id"));
    if (usage.path("datasetKey").asInt(-1) != bridge.dataset)
      throw new IllegalStateException("Taxon belongs to another dataset");
    var name = usage.path("name");
    String scientific = required(name, "scientificName");
    String parent = usage.path("parentId").asText("");
    if (!parent.isBlank()) parent = code(parent);
    if (id.equals(parent)) throw new IllegalStateException("Taxon is its own parent: " + id);
    String label = usage.path("label").asText(scientific);
    String description =
        label
            + " ("
            + required(name, "rank")
            + ", "
            + required(usage, "status")
            + "). Catalogue of Life "
            + bridge.version
            + "; dataset "
            + bridge.dataset
            + ", taxon "
            + id
            + ". https://www.checklistbank.org/dataset/"
            + bridge.dataset
            + "/taxon/"
            + encode(id);
    var documentation = bridge.documentation.get(id);
    if (documentation == null) {
      documentation = TaxonDocumentation.build(client, bridge.endpoint, bridge.dataset,
          bridge.timeout, bridge.metadata, usage);
      // Retry optional failures rather than storing them for the bridge lifetime.
      if (documentation.notifications().isEmpty()) bridge.documentation.put(id, documentation);
    }
    return new TaxonIdentity(
        id,
        "taxon_"
            + bridge.dataset
            + "_"
            + HexFormat.of().formatHex(id.getBytes(StandardCharsets.UTF_8)),
        bridge.request.name(),
        parent.isBlank() ? bridge.request.rootIdentity() : null,
        parent.isBlank() ? List.of() : List.of(parent),
        documentation.urls(),
        description,
        label,
        score,
        bridge.request.name() + ":" + id,
        documentation.notifications());
  }

  private Identity failure(Bridge bridge, String id, String message) {
    return new TaxonIdentity(
        id,
        null,
        bridge.request.name(),
        null,
        List.of(),
        Map.of(),
        message,
        id,
        0,
        null,
        List.of(Notification.error("TAXA: " + message)));
  }

  private Bridge bridge(String id) {
    var result = id == null ? null : bridges.get(id);
    if (result == null)
      throw new IllegalArgumentException("Unknown or released taxa configuration ID");
    return result;
  }

  private static boolean acceptedStatus(JsonNode usage) {
    return Set.of("accepted", "provisionally accepted")
        .contains(usage.path("status").asText().toLowerCase(Locale.ROOT));
  }

  private static boolean synonymStatus(JsonNode usage) {
    return "synonym".equals(usage.path("status").asText().toLowerCase(Locale.ROOT));
  }

  private static String required(JsonNode node, String key) {
    if (!node.path(key).isTextual() || node.path(key).asText().isBlank())
      throw new IllegalStateException("ChecklistBank omitted " + key);
    return node.path(key).asText();
  }

  private static String code(String id) {
    if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
      throw new IllegalArgumentException(
          "Expected a release-scoped taxon code; use search or reconcile for names");
    }
    return id;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String normalizedRank(String value) {
    if (value == null || value.isBlank()) return null;
    var rank = value.toUpperCase(Locale.ROOT);
    if (!TaxaRanks.ALL.contains(rank))
      throw new IllegalArgumentException("Unknown taxonomic rank: " + value);
    return rank;
  }

  private static int integer(
      Map<String, Object> parameters, String key, Integer fallback, int min, int max) {
    var value = parameters.getOrDefault(key, fallback);
    try {
      int result = new java.math.BigDecimal(String.valueOf(value)).intValueExact();
      if (result < min || result > max) throw new ArithmeticException();
      return result;
    } catch (NumberFormatException | ArithmeticException e) {
      throw new IllegalArgumentException(
          key + " must be an integer between " + min + " and " + max);
    }
  }

  private static String endpoint(Object value) {
    if (!(value instanceof String text))
      throw new IllegalArgumentException("endpoint must be a URL string");
    var uri = URI.create(text);
    boolean loopback =
        Set.of("localhost", "127.0.0.1", "[::1]").contains(String.valueOf(uri.getHost()));
    if (uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !("https".equals(uri.getScheme()) || (loopback && "http".equals(uri.getScheme())))) {
      throw new IllegalArgumentException(
          "endpoint must use HTTPS, or HTTP on loopback for local testing");
    }
    return text.replaceAll("/+$", "");
  }

  private static final class Bridge {
    final ConfigurationRequest request;
    final int dataset, limit;
    final String endpoint, version;
    final Duration timeout;
    final Map<String, JsonNode> cache;
    final JsonNode metadata;
    final Map<String, TaxonDocumentation.Result> documentation;

    Bridge(
        ConfigurationRequest request,
        int dataset,
        String endpoint,
        Duration timeout,
        int limit,
        int cacheSize,
        JsonNode metadata) {
      this.request = request;
      this.dataset = dataset;
      this.endpoint = endpoint;
      this.timeout = timeout;
      this.limit = limit;
      this.metadata = metadata.deepCopy();
      this.version = metadata.path("version").asText("unknown version");
      documentation = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TaxonDocumentation.Result> eldest) {
          return size() > cacheSize;
        }
      };
      cache =
          new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, JsonNode> eldest) {
              return size() > cacheSize;
            }
          };
    }
  }
}
