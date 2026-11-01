package com.migration.kitchensink.infrastructure.legacydatabase;

import com.migration.kitchensink.domaine.models.Member;
import com.migration.kitchensink.domaine.models.NewMember;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes members into the legacy MySQL database, the source of truth during the migration (CDC copies them to
 * MongoDB). The schema belongs to the legacy app (Hibernate): this class never changes it.
 *
 * Ids come from Hibernate's Member_SEQ table, shared with the legacy app: the row is locked and moved forward in
 * one transaction, so the legacy app and this class never hand out the same id. Never use max(id) + 1: the legacy
 * app hands out ids from blocks it reserved in memory.
 */
@Component
public class LegacyMemberPersistence {

	private final JdbcClient jdbcClient;

	public LegacyMemberPersistence(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	// Same comparison as the legacy check and unique key (MySQL utf8mb4_0900_ai_ci: case- and accent-insensitive)
	public boolean existsByEmail(String email) {
		return jdbcClient.sql("SELECT COUNT(*) FROM Member WHERE email = ?").param(email).query(Long.class).single() > 0;
	}

	/**
	 * Reserves the next id from Member_SEQ and inserts the member, in one transaction: the Member_SEQ row stays
	 * locked until the insert commits, and a failed insert also gives the id back.
	 * @return the stored member, with its id
	 */
	@Transactional
	public Member insert(NewMember member) {
		long id = jdbcClient.sql("SELECT next_val FROM Member_SEQ FOR UPDATE").query(Long.class).single();
		jdbcClient.sql("UPDATE Member_SEQ SET next_val = next_val + 1").update();
		jdbcClient.sql("INSERT INTO Member (id, email, name, phone_number) VALUES (?, ?, ?, ?)")
			.params(id, member.email(), member.name(), member.phoneNumber())
			.update();
		return new Member(id, member.name(), member.email(), member.phoneNumber());
	}

}
