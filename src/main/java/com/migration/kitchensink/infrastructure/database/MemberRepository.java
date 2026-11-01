package com.migration.kitchensink.infrastructure.database;

import java.util.List;

import org.springframework.data.mongodb.core.annotation.Collation;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MemberRepository extends MongoRepository<MemberDocument, Long> {

	// Same order as legacy MySQL (utf8mb4_0900_ai_ci): case- and accent-insensitive
	@Collation("{ 'locale' : 'en', 'strength' : 1 }")
	List<MemberDocument> findAllByOrderByNameAsc();

}
