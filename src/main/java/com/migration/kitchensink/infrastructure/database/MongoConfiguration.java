package com.migration.kitchensink.infrastructure.database;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

/**
 * Same converter as Spring Boot's default, but without Spring Data's "_class" type hint: stored documents
 * keep exactly the member fields, which the members collection validator requires (no extra fields).
 */
@Configuration(proxyBeanMethods = false)
public class MongoConfiguration {

	@Bean
	MappingMongoConverter mappingMongoConverter(MongoDatabaseFactory factory, MongoMappingContext context,
			MongoCustomConversions conversions) {
		MappingMongoConverter converter = new MappingMongoConverter(new DefaultDbRefResolver(factory), context);
		converter.setCustomConversions(conversions);
		converter.setCodecRegistryProvider(factory);
		converter.setTypeMapper(new DefaultMongoTypeMapper(null));
		return converter;
	}

}
