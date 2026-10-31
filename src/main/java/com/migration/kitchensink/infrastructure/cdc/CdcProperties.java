package com.migration.kitchensink.infrastructure.cdc;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CDC settings (migration.cdc.*): the legacy MySQL database read by Debezium and where Debezium keeps
 * its state (binlog offsets and schema history) between restarts.
 */
@ConfigurationProperties("migration.cdc")
public record CdcProperties(boolean enabled, Mysql mysql, Path stateDirectory) {

	/**
	 * @param serverId replication client id, must be unique among the clients of this MySQL server
	 */
	public record Mysql(String host, int port, String user, String password, String database, String table,
			long serverId) {

	}

}
