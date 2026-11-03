package com.migration.kitchensink.infrastructure.database;

import com.migration.kitchensink.domaine.models.Member;

/**
 * A change of a member in the legacy database, to apply to MongoDB (CDC). Migration only: not a domain concept.
 */
public sealed interface MemberChange {

	// created, updated or read by the initial snapshot: store the member as it is in legacy
	record Upsert(Member member) implements MemberChange {
	}

	// deleted in legacy
	record Removal(long id) implements MemberChange {
	}

}
