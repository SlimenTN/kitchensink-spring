package com.migration.kitchensink.infrastructure.database;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface MemberRepository extends MongoRepository<MemberDocument, Long> {

}
