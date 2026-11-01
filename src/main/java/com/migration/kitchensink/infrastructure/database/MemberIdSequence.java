package com.migration.kitchensink.infrastructure.database;

import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Projections.include;
import static com.mongodb.client.model.Updates.inc;
import static com.mongodb.client.model.Updates.setOnInsert;

/**
 * Member ids once MongoDB is the write store (after cutover), like the legacy Member_SEQ table: one counter document,
 * counters/members, incremented atomically, so concurrent registrations never get the same id.
 *
 * On the first registration the counter starts at the biggest member id: the first new member gets biggest id + 1.
 * Writes are stopped at cutover until CDC has copied the last legacy members, so no legacy id comes after that.
 */
@Component
public class MemberIdSequence {

	static final String COUNTERS = "counters";

	static final String MEMBERS = "members";

	private final MongoTemplate mongoTemplate;

	public MemberIdSequence(MongoTemplate mongoTemplate) {
		this.mongoTemplate = mongoTemplate;
	}

	public long next() {
		var counters = mongoTemplate.getCollection(COUNTERS);
		if (counters.countDocuments(eq("_id", MEMBERS)) == 0) {
			// created once, at the biggest id; a no-op if another registration created it in the meantime
			counters.updateOne(eq("_id", MEMBERS), setOnInsert("seq", biggestMemberId()),
					new UpdateOptions().upsert(true));
		}
		Document counter = counters.findOneAndUpdate(eq("_id", MEMBERS), inc("seq", 1L),
				new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
		return counter.get("seq", Number.class).longValue();
	}

	private long biggestMemberId() {
		Document biggest = mongoTemplate.getCollection(MEMBERS)
			.find()
			.sort(Sorts.descending("_id"))
			.projection(include("_id"))
			.first();
		return biggest == null ? 0L : biggest.get("_id", Number.class).longValue();
	}

}
