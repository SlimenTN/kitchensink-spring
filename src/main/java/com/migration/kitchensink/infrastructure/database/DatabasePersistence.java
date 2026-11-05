package com.migration.kitchensink.infrastructure.database;

import java.util.List;
import java.util.Optional;

import com.migration.kitchensink.domaine.exceptions.EmailAlreadyTakenException;
import com.migration.kitchensink.domaine.models.Member;
import com.migration.kitchensink.domaine.models.NewMember;
import com.migration.kitchensink.domaine.services.Store;
import com.migration.kitchensink.infrastructure.legacydatabase.LegacyDatabasePersistence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.BulkOperations.BulkMode;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * The single persistence entry point of the domain: it decides which database is used.
 *
 * Reads and CDC synchronization use MongoDB (memberRepository), except the read right after a page registration
 * (findAllOrderedByNameFromWriteStore). Registrations (create, email check) go to
 * the database selected by migration.write-store: "legacy" = the legacy MySQL database, source of truth during the
 * migration (CDC copies new members to MongoDB); "new" = MongoDB, once all write traffic is on the new system (ids from
 * MemberIdSequence: biggest id + 1, case- and accent-insensitive unique email like MySQL).
 */
@Component
public class DatabasePersistence implements Store {

	static final String LEGACY = "legacy";

	static final String NEW = "new";

	private final MemberRepository memberRepository;

	private final LegacyDatabasePersistence legacyRepository;

	private final MemberIdSequence idSequence;

	private final MongoTemplate mongoTemplate;

	private final String writeStore;

	public DatabasePersistence(
			final MemberRepository memberRepository,
			final LegacyDatabasePersistence legacyRepository,
			final MemberIdSequence idSequence,
			final MongoTemplate mongoTemplate,
			final @Value("${migration.write-store}") String writeStore) {

		if (!LEGACY.equals(writeStore) && !NEW.equals(writeStore)) {
			throw new IllegalArgumentException(
					"migration.write-store must be '" + LEGACY + "' or '" + NEW + "', got '" + writeStore + "'");
		}
		this.memberRepository = memberRepository;
		this.legacyRepository = legacyRepository;
		this.idSequence = idSequence;
		this.mongoTemplate = mongoTemplate;
		this.writeStore = writeStore;
	}

	public Optional<Member> findById(long id) {
		return memberRepository.findById(id).map(DatabasePersistence::toMember);
	}

	// Sorted like the legacy list: by name, ignoring case and accents
	public List<Member> findAllOrderedByName() {
		return memberRepository.findAllByOrderByNameAsc().stream().map(DatabasePersistence::toMember).toList();
	}

	/**
	 * Read-your-writes: all members as the write store has them, including a registration made just now (with "legacy",
	 * CDC copies it to MongoDB only about a second later). The only read that does not always use MongoDB; used for the
	 * page returned after a registration, like the legacy page.
	 */
	public List<Member> findAllOrderedByNameAfterRegistration() {
		if (LEGACY.equals(writeStore)) {
			return legacyRepository.findAllOrderedByName();
		}
		return findAllOrderedByName();
	}

	// Checked in the database that receives new members
	public boolean existsByEmail(String email) {
		if (LEGACY.equals(writeStore)) {
			return legacyRepository.existsByEmail(email);
		}
		return memberRepository.existsByEmail(email);
	}

	/**
	 * Registers a new member in the write store.
	 * @return the stored member, with its id
	 * @throws EmailAlreadyTakenException (write store "new") when the email was registered at the same moment: the
	 * unique email index rejects the second member
	 */
	public Member create(NewMember member) {
		if (LEGACY.equals(writeStore)) {
			return legacyRepository.insert(member);
		}
		MemberDocument document = new MemberDocument(
				idSequence.next(),
				member.name(),
				member.email(),
				member.phoneNumber()
		);
		try {
			// insert, not save: never replaces an existing member
			memberRepository.insert(document);
		}
		catch (DuplicateKeyException exception) {
			throw new EmailAlreadyTakenException(member.email());
		}
		return toMember(document);
	}

	/**
	 * CDC: applies a batch of legacy changes to MongoDB (always), in one bulk write: upsert by _id, delete by _id
	 * (unknown ids are ignored). One round trip per Debezium batch instead of one per member.
	 *
	 * ORDERED: the changes are applied in the binlog order, so two changes of the same row (or a delete then an insert
	 * reusing an email) end like in legacy. If a write fails, the rest of the batch is not applied; the batch is not
	 * marked processed either, so CDC sends it again on the next start (upserts and deletes by _id can be replayed).
	 */
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

	private static Member toMember(MemberDocument document) {
		return new Member(document.id(), document.name(), document.email(), document.phoneNumber());
	}

}
