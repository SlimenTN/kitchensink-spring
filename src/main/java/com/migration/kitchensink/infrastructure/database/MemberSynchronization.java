package com.migration.kitchensink.infrastructure.database;

import java.util.List;

import com.migration.kitchensink.domaine.models.MemberChange;

/**
 * CDC writes on the members collection, added to {@link MemberRepository} (implemented by
 * {@link MemberSynchronizationImpl}).
 */
public interface MemberSynchronization {

	/**
	 * Applies the changes in their order, in one bulk write: upsert by _id, delete by _id (unknown ids are ignored).
	 */
	void synchronize(List<MemberChange> changes);

}
