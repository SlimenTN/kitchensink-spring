package com.migration.kitchensink.infrastructure.database;

import java.util.List;

import com.migration.kitchensink.domaine.models.Member;
import com.migration.kitchensink.domaine.models.MemberChange;

import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.BulkOperations.BulkMode;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * One round trip per Debezium batch instead of one per member (the initial snapshot of a large table is thousands of
 * members per batch).
 *
 * ORDERED: the changes are applied in the binlog order, so two changes of the same row (or a delete then an insert
 * reusing an email) end like in legacy. If a write fails, the rest of the batch is not applied; the batch is not marked
 * processed either, so CDC sends it again on the next start (upserts and deletes by _id can be replayed safely).
 */
class MemberSynchronizationImpl implements MemberSynchronization {

	private final MongoTemplate mongoTemplate;

	MemberSynchronizationImpl(MongoTemplate mongoTemplate) {
		this.mongoTemplate = mongoTemplate;
	}

	@Override
	public void synchronize(List<MemberChange> changes) {
		if (changes.isEmpty()) {
			return; // MongoDB rejects an empty bulk write
		}
		BulkOperations bulk = mongoTemplate.bulkOps(BulkMode.ORDERED, MemberDocument.class);
		for (MemberChange change : changes) {
			switch (change) {
				// every field is set: same result as replacing the whole document
				case MemberChange.Upsert(Member member) -> bulk.upsert(byId(member.id()),
						Update.update("name", member.name())
							.set("email", member.email())
							.set("phoneNumber", member.phoneNumber()));
				case MemberChange.Removal(long id) -> bulk.remove(byId(id));
			}
		}
		bulk.execute();
	}

	private static Query byId(long id) {
		return Query.query(where("_id").is(id));
	}

}
