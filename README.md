# k.LAB taxonomic authority

`klab.authority.taxa` is an embeddable, searchable authority for taxonomic identities at any rank
available in a **pinned Catalogue of Life Extended Release (COL XR)** in ChecklistBank. It supplies
accepted taxon codes, scientific labels, descriptions, immediate parents, scientific/vernacular
name search, and explicit scientific-name reconciliation. Codelists are not implemented.

The provider uses the current `org.integratedmodelling.klab.api.services.Authority` contract.
Its authority URN is `klab.authority.taxa`, independently of the local name chosen by a worldview.
The Java annotation declares authority version `1.0.0`; the component build is currently a SNAPSHOT.

## Taxonomy and identity policy

GBIF now recommends COL XR; the old GBIF numeric backbone is frozen and remains available for
compatibility. This provider implements COL XR through the ChecklistBank API, rather than copying
the legacy `/v1/species` numeric-code authority. See [GBIF's migration guide](https://data-blog.gbif.org/post/catalogue-of-life-taxonomic-backbone/)
and the [ChecklistBank API](https://api.checklistbank.org/openapi).

A ChecklistBank taxon code is unique **within its dataset**, not across every checklist or release.
The worldview bridge pins the dataset; the semantic identity is `(datasetKey, acceptedTaxonId)`
within that binding. Different releases must not be treated as automatically equivalent.
Generated concept names include the dataset key and a collision-free hexadecimal encoding of the
accepted code. The Reasoner isolates each worldview-local binding in its own internal ontology.

No mutable project or “latest release” alias is accepted. Configuration requires a positive numeric
dataset key whose metadata declares `origin=xrelease`, `sourceKey=3` (Catalogue of Life), no deletion,
and public access. There is no default release and no silent upgrade. Release availability must be
managed with the worldview: pinning makes content reproducible while available, but does not
guarantee indefinite hosting. A deleted older release was observed during development; production
needs a retained upstream release or a preserved local ChecklistBank instance.

The provider accepts `accepted` and `provisionally accepted` taxa. A `synonym` redirects to its
accepted taxon, producing the same identity and concept name, rather than a synonym subclass.
Bare/unplaced names, ambiguous synonyms, and misapplied names cannot become taxonomic identities.
Provisional placement is stated in the description. Extinct taxa and any other accepted taxa in the
chosen release are included; the provider does not add an extant/natural-only editorial filter.
Coverage and correctness remain those of the pinned taxonomy, not a claim that all species are known.

## Worldview bridge

```kwv
identity TaxonomicIdentity
    requires authority TAXA {
        urn: "klab.authority.taxa",
        datasetKey: 312578
    }
;
```

`312578` is a concrete COL XR release inspected during development (2025-10-10 XR), not a
recommended permanent default. Select and retain the release appropriate for the worldview.
The binding name `TAXA` is arbitrary. Once configured, `TAXA:5WZLF` requests that release's taxon
`5WZLF`. Numeric legacy GBIF keys are not automatically translated to COL codes.

| Parameter | Default | Contract |
| --- | --- | --- |
| `urn` | Required | Exactly `klab.authority.taxa`. |
| `datasetKey` | Required | Positive integer key of a public, available COL XR release. Integer strings are also accepted; aliases such as `3LR` are rejected. |
| `endpoint` | `https://api.checklistbank.org` | ChecklistBank base URL. HTTPS required, except loopback HTTP for local tests/development. May include a base path. |
| `timeoutSeconds` | `20` | Whole HTTP request deadline, 1–120 seconds. |
| `searchLimit` | `25` | First-page candidate limit, 1–100. |
| `cacheSize` | `5000` | Maximum cached name usages per bridge, 1–100000. |

Unknown parameters are rejected to catch configuration typos. Direct provider `configure()` creates
a transient handle and bounded LRU cache; the Reasoner reuses identical active declarations and
wraps the provider with its shared persistent authority cache. The externally returned bridge ID
contains the worldview/local configuration name and a fingerprint of the root, full parameter map,
provider implementation and cache policy. It remains stable across restart/reload when these match.
`releaseConfiguration()` invalidates the live handle but retains persisted results for later reuse.

Persistence belongs to the Reasoner core, not TAXA. It stores successful identities, search pages
and reconciliation results under its `services/reasoner/authority-cache` data directory. Cached
records use a core DTO independent of plug-in classes; every ancestor resolved by the Reasoner is
cached separately. Different datasets, endpoints, roots, worldviews, local names, provider artifacts
and cache-policy revisions select different partitions. Search candidates never bypass full code
validation by seeding the identity cache. Failed or diagnostic-bearing responses are not persisted.

TAXA declares `getCachePolicy()` revision `col-xr-1`: pinned taxon identities have no time expiry,
while search and reconciliation expire after one day. Other authorities use the shared default
(one day for identities, five minutes for queries) or override/disable it. Reconfiguration still
restores a live provider handle and validates release metadata; the cache is not an automatic
offline-mode bypass of configuration or worldview integrity checks. Direct provider use outside a
Reasoner retains only its in-memory LRU.

## Hierarchy and the worldview root

Identity lookup uses `GET /dataset/{datasetKey}/nameusage/{id}`. For an accepted taxon,
ChecklistBank's `parentId` is its immediate taxonomic parent. The provider preserves every parent,
including intermediate and infraspecific ranks; it does not reconstruct ranks from names.

Only a top-level taxon with no parent returns the configured worldview root URN as `baseIdentity`.
Descendants return their immediate parent and no extra root edge. The Reasoner recursively
materializes unknown parents until an already known concept is reached. All rank views use the
same binding and hierarchy; a species search does not prevent resolving a genus or kingdom parent.

Before returning a resolved identity, the provider validates its complete ancestry to a top-level
taxon. Missing/non-accepted parents, self-links, cycles, and paths exceeding 256 links produce an
error identity. Synonym redirects also have cycle/depth checks. This validates provider consistency
even when the Reasoner stops at a previously materialized parent. Responses are cached within the
bridge to reuse ancestry lookups; failures in transport/JSON parsing are not cached.

Resolved identities have a score of `1`, a scientific label, rank/status/release description,
canonical local locator (`TAXA:5WZLF`), immediate parent IDs, and no typed parent relationships.
Descriptions include a ChecklistBank source link. Unknown codes and lookup failures return error
notifications, which the Reasoner must reject before creating concepts. Unknown/released bridge IDs
are programming/configuration errors and throw an exception.

## Search and rank sub-authorities

```java
Authority provider = new TaxaAuthority();
String bridgeId = provider.configure(request);
List<Authority.Identity> results = provider.search("white shark", "SPECIES", bridgeId);
Authority.Identity selected = provider.resolveIdentity(bridgeId, results.getFirst().getId());
```

Search calls `/dataset/{datasetKey}/nameusage/search` with both `SCIENTIFIC_NAME` and
`VERNACULAR_NAME` content and upstream relevance ordering. Scientific and common names can be
ambiguous: the caller must let the user choose a code. Synonym hits canonicalize and duplicate
accepted identities are removed. Bare/unplaced names and unsupported statuses are omitted.
Search scores are `1 / upstreamPosition`, preserving ordering; they are not confidence probabilities.
Only the first configured page is returned. A transport failure throws an exception; it is never
reported as an empty successful search.

The sub-authority is an exact rank filter implemented using `minRank=maxRank`, with a defensive
check against each candidate's source rank. `subAuthority("SPECIES")` creates a reusable view whose
search defaults to that filter. Configuration state and code lookup are shared with the provider.
All ChecklistBank rank names captured in `TaxaRanks` are advertised by runtime capabilities,
including `DOMAIN`, `KINGDOM`, `FAMILY`, `GENUS`, `SPECIES`, `SUBSPECIES`, `VARIETY`, and `FORM`.
The component annotation advertises common ranks; capabilities contain the complete vocabulary.
Ranks restrict search, not the semantic universe. They do not create independent vocabularies.

The language spelling `TAXA.SPECIES:<code>` resolves through the same base bridge as `TAXA:<code>`.
The provider declares `Capabilities.areSubAuthoritiesSearchFilters()`; the Reasoner accepts only
advertised suffixes and keeps the base name in canonical locators. An explicitly configured dotted
binding takes precedence. Other providers do not receive this alias behavior unless they opt in.
Search callers pass the suffix as `search(..., rank, bridgeId)` or use `subAuthority(rank)`.

## Explicit reconciliation

```java
Authority.Identity match = provider.reconcile(bridgeId,
    Map.of("scientificName", "Oenanthe", "kingdom", "Plantae", "authorship", "L."));
```

The shared Authority API now has an optional `reconcile(configurationId, fields)` operation,
advertised by `Capabilities.isReconciliationSupported()`. TAXA supports it; code lookup remains
strict (`isFuzzy=false`). Reconciliation calls `/dataset/{datasetKey}/match/nameusage`, using the
same pinned dataset. `scientificName` is required. Optional hints include `authorship`, `code`,
`rank` and classification fields (`kingdom`, `phylum`, `class`, `order`, `family`, `genus`, etc.;
the accepted field list is in `MATCH_HINTS`). Arbitrary upstream parameters are rejected.

Only `exact`, `variant`, and `canonical` matches with `match=true` are accepted, followed by normal
code resolution and hierarchy validation. `ambiguous`, `higherrank`, `none`, and `unsupported`
results return error notifications. In particular, ChecklistBank may return `match=true` together
with `type=ambiguous`; that is still rejected. Common names belong in search rather than scientific
reconciliation. The provider never converts a scientific name to the first autocomplete result.

Bracketed language expressions use `TAXA:[...]`. This implementation does not interpret them;
reconciliation is an explicit Java API operation. End-to-end bracket payload transport, a structured
expression format, and service/UI reconciliation remain further work.

## Component build and deployment

Requires Java 21 and locally installed current k.LAB artifacts, including the updated Authority API
and `klab.product` Maven plugin. Build and test with:

```shell
mvn test
mvn package
```

The packaging plugin attaches the component archive; the PF4J entry point is `ComponentPlugin`.
Host dependency declarations name the actual `klab.services.reasoner.server` and
`klab.services.resources.server` artifacts. Core services and Jackson are host-provided dependencies,
so the component archive does not bundle copies of the platform or its transitive test libraries.
Resources indexes/distributes the component; a Reasoner
instantiates the authority. The authority is embeddable and needs no GBIF account for public reads.
HTTP requests identify the client, impose a whole-request deadline and a 4 MiB response cap, and do
not automatically retry rate limits or failed requests. Tests use a local HTTP fixture server and
do not require network access.

Authority components must ultimately originate from the authorized Resources contributors to the
loaded worldview. That integrity policy, service-scope startup discovery, distributed worldview
assembly and component revision invalidation are still pending in `klab-services`; this component
does not establish or bypass trust. The full-local workflow and authorized remote providers serving
the same worldview are part of that service contract.

## Legacy comparison and remaining integration work

The 0.11 `GBIFAuthority` was consulted locally. Its numeric `/v1/species` lookup, global MapDB cache,
`getIdentity`/`setup` signatures, rank-name inference, and suggest-only search are replaced by
release-scoped ChecklistBank data, provider-held bridges, explicit parents and full-text search.
The historical `PHLYUM` spelling is rejected; `PHYLUM` is valid. No global cache-clearing parameter,
GBIF numeric-key migration, codelists, or speculative documentation endpoint is carried forward.

Provider tests cover configuration validation, independent bridges, release, accepted/synonym
canonicalization, parent/root semantics, cycles/missing parents, subspecies, rank search, URL
encoding, reconciliation ambiguity, malformed responses and cross-dataset rejection. Live public
API inspection verified the formats and the ambiguous-match behavior; it is not part of unit tests.

Remaining service work includes identity/search/reconciliation REST and
UI transport, bracket expressions, provenance enforcement and component update lifecycle. A full
Resources-to-Reasoner worldview ingestion test is needed before calling the stack operational.
Offline taxonomy snapshots, release migration/equivalence, search pagination, vernacular
language preferences, and semantic-distance delegation are future features.
