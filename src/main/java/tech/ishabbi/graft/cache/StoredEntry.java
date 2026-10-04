package tech.ishabbi.graft.cache;

import tech.ishabbi.graft.LocatorSuggestion;

/** One remembered locator. {@code learnedAt} is an ISO-8601 instant assigned by the store. */
public record StoredEntry(LocatorSuggestion suggestion, String origin, String learnedAt, String framework) {}
