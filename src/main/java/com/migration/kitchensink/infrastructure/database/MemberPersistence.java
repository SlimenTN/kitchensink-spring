package com.migration.kitchensink.infrastructure.database;

import java.util.Optional;

import com.migration.kitchensink.domaine.models.Member;

import org.springframework.stereotype.Component;

// Reads members through MemberRepository and maps MemberDocument -> Member
@Component
public class MemberPersistence {

	private final MemberRepository memberRepository;

	public MemberPersistence(MemberRepository memberRepository) {
		this.memberRepository = memberRepository;
	}

	public Optional<Member> findById(long id) {
		return memberRepository.findById(id).map(MemberPersistence::toMember);
	}

	private static Member toMember(MemberDocument document) {
		return new Member(document.id(), document.name(), document.email(), document.phoneNumber());
	}

}
