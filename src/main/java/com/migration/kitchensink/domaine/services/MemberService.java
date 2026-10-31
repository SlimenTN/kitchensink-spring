package com.migration.kitchensink.domaine.services;

import com.migration.kitchensink.domaine.exceptions.MemberNotFoundException;
import com.migration.kitchensink.domaine.models.Member;
import com.migration.kitchensink.infrastructure.database.MemberPersistence;

import org.springframework.stereotype.Service;

@Service
public class MemberService {

	private final MemberPersistence memberPersistence;

	public MemberService(MemberPersistence memberPersistence) {
		this.memberPersistence = memberPersistence;
	}

	/**
	 * @throws MemberNotFoundException when no member has this id
	 */
	public Member findById(long id) {
		return memberPersistence.findById(id).orElseThrow(() -> new MemberNotFoundException(id));
	}

	/**
	 * Stores the member as it exists in the legacy system (created, updated or snapshotted).
	 */
	public void synchronize(Member member) {
		memberPersistence.save(member);
	}

	/**
	 * Removes a member deleted from the legacy system; unknown ids are ignored.
	 */
	public void remove(long id) {
		memberPersistence.deleteById(id);
	}

}
