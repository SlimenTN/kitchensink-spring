package com.migration.kitchensink.infrastructure.database;

import java.util.List;
import java.util.Optional;

import com.migration.kitchensink.domaine.models.Member;
import com.migration.kitchensink.domaine.models.NewMember;
import com.migration.kitchensink.infrastructure.legacydatabase.LegacyMemberPersistence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The single persistence entry point of the domain: it decides which database is used.
 *
 * Reads and CDC synchronization always use MongoDB (memberRepository). Registrations (create, email check) go to
 * the database selected by migration.write-store: "legacy" = the legacy MySQL database, source of truth during the
 * migration (CDC copies new members to MongoDB); "new" = MongoDB, once all write traffic is on the new system.
 */
@Component
public class MemberPersistence {

	static final String LEGACY = "legacy";

	static final String NEW = "new";

	private final MemberRepository memberRepository;

	private final LegacyMemberPersistence legacyRepository;

	private final String writeStore;

	public MemberPersistence(
			final MemberRepository memberRepository,
			final LegacyMemberPersistence legacyRepository,
			final @Value("${migration.write-store}") String writeStore) {

		if (!LEGACY.equals(writeStore) && !NEW.equals(writeStore)) {
			throw new IllegalArgumentException(
					"migration.write-store must be '" + LEGACY + "' or '" + NEW + "', got '" + writeStore + "'");
		}
		this.memberRepository = memberRepository;
		this.legacyRepository = legacyRepository;
		this.writeStore = writeStore;
	}

	public Optional<Member> findById(long id) {
		return memberRepository.findById(id).map(MemberPersistence::toMember);
	}

	// Sorted like the legacy list: by name, ignoring case and accents
	public List<Member> findAllOrderedByName() {
		return memberRepository.findAllByOrderByNameAsc().stream().map(MemberPersistence::toMember).toList();
	}

	// Checked in the database that receives new members
	public boolean existsByEmail(String email) {
		if (LEGACY.equals(writeStore)) {
			return legacyRepository.existsByEmail(email);
		}
		throw newWriteStoreNotAvailable();
	}

	/**
	 * Registers a new member in the write store.
	 * @return the stored member, with its id
	 */
	public Member create(NewMember member) {
		if (LEGACY.equals(writeStore)) {
			return legacyRepository.insert(member);
		}
		throw newWriteStoreNotAvailable();
	}

	// CDC: stores the member as it exists in the legacy database (upsert by _id); always MongoDB
	public void synchronize(Member member) {
		memberRepository.save(toDocument(member));
	}

	// CDC: always MongoDB
	public void deleteById(long id) {
		memberRepository.deleteById(id);
	}

	// writing to MongoDB first needs its own id counter (started far above Member_SEQ) and a case-insensitive
	// unique index on email
	private static IllegalStateException newWriteStoreNotAvailable() {
		return new IllegalStateException("migration.write-store '" + NEW + "' is not available yet");
	}

	private static MemberDocument toDocument(Member member) {
		return new MemberDocument(member.id(), member.name(), member.email(), member.phoneNumber());
	}

	private static Member toMember(MemberDocument document) {
		return new Member(document.id(), document.name(), document.email(), document.phoneNumber());
	}

}
