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

	// Upsert by _id: inserts a new member or replaces the existing one
	public void save(Member member) {
		memberRepository.save(toDocument(member));
	}

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
