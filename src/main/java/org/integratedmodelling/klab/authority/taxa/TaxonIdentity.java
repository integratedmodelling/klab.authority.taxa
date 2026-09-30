package org.integratedmodelling.klab.authority.taxa;

import java.util.List;
import org.integratedmodelling.klab.api.services.Authority;
import org.integratedmodelling.klab.api.services.runtime.Notification;

/** Immutable provider data; aliases and synonyms identify the same accepted taxon. */
public record TaxonIdentity(
    String id, String conceptName, String authorityName, String baseIdentity,
    List<String> parentIds, String description, String label, float score,
    String locator, List<Notification> notifications) implements Authority.Identity {
  public TaxonIdentity {
    parentIds = List.copyOf(parentIds);
    notifications = List.copyOf(notifications);
  }
  @Override public String getId() { return id; }
  @Override public String getConceptName() { return conceptName; }
  @Override public String getAuthorityName() { return authorityName; }
  @Override public String getBaseIdentity() { return baseIdentity; }
  @Override public List<String> getParentIds() { return parentIds; }
  @Override public List<String> getParentRelationship() { return List.of(); }
  @Override public String getDescription() { return description; }
  @Override public String getLabel() { return label; }
  @Override public float getScore() { return score; }
  @Override public String getLocator() { return locator; }
  @Override public List<Notification> getNotifications() { return notifications; }
}
