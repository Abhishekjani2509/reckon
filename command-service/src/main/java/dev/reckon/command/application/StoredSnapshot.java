package dev.reckon.command.application;

/**
 * A snapshot as it exists in the store: the version it reflects and its serialised state.
 */
public record StoredSnapshot(long version, String stateJson) {}
