package com.migration.kitchensink.domaine.models;

/**
 * A change of a member in the legacy database, to apply to the new system (CDC).
 */
public sealed interface MemberChange {

	// created, updated or read by the initial snapshot: store the member as it is in legacy
	record Upsert(Member member) implements MemberChange {
	}

	// deleted in legacy
	record Removal(long id) implements MemberChange {
	}

}
