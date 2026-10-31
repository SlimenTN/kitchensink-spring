package com.migration.kitchensink.infrastructure.database;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

// _id is the legacy SQL id (NumberLong), copied as-is
@Document("members")
public record MemberDocument(@Id Long id, String name, String email, String phoneNumber) {

}
