package com.migration.kitchensink.infrastructure.database;

import java.util.List;
import java.util.Optional;

import com.migration.kitchensink.domaine.exceptions.EmailAlreadyTakenException;
import com.migration.kitchensink.domaine.models.Member;
import com.migration.kitchensink.domaine.models.NewMember;
import com.migration.kitchensink.infrastructure.legacydatabase.LegacyMemberPersistence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * The single persistence entry point of the domain: it decides which database is used.
 *
 * Reads and CDC synchronization always use MongoDB (memberRepository). Registrations (create, email check) go to
 * the database selected by migration.write-store: "legacy" = the legacy MySQL database, source of truth during the
 * migration (CDC copies new members to MongoDB); "new" = MongoDB, once all write traffic is on the new system (ids from
 * MemberIdSequence: biggest id + 1, case- and accent-insensitive unique email like MySQL).
 */
@Component
public class MemberPersistence {

	static final String LEGACY = "legacy";

	static final String NEW = "new";

	private final MemberRepository memberRepository;

	private final LegacyMemberPersistence legacyRepository;

	private final MemberIdSequence idSequence;

	private final String writeStore;

	public MemberPersistence(
			final MemberRepository memberRepository,
			final LegacyMemberPersistence legacyRepository,
			final MemberIdSequence idSequence,
			final @Value("${migration.write-store}") String writeStore) {

		if (!LEGACY.equals(writeStore) && !NEW.equals(writeStore)) {
			throw new IllegalArgumentException(
					"migration.write-store must be '" + LEGACY + "' or '" + NEW + "', got '" + writeStore + "'");
		}
		this.memberRepository = memberRepository;
		this.legacyRepository = legacyRepository;
		this.idSequence = idSequence;
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

	// CDC: stores the member as it exists in the legacy database (upsert by _id); always MongoDB
	public void synchronize(Member member) {
		memberRepository.save(toDocument(member));
	}

	// CDC: always MongoDB
	public void deleteById(long id) {
		memberRepository.deleteById(id);
	}

	private static MemberDocument toDocument(Member member) {
		return new MemberDocument(member.id(), member.name(), member.email(), member.phoneNumber());
	}

	private static Member toMember(MemberDocument document) {
		return new Member(document.id(), document.name(), document.email(), document.phoneNumber());
	}

}
