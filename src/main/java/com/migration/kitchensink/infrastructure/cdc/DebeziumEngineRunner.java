package com.migration.kitchensink.infrastructure.cdc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.DebeziumEngine.RecordCommitter;
import io.debezium.engine.format.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.SmartLifecycle;

/**
 * Runs the embedded Debezium engine with the application: streams the legacy MySQL Member table
 * (initial snapshot, then binlog) to {@link MemberChangeHandler}.
 *
 * An event is marked as processed only after the handler succeeded, so a failed write is retried
 * on the next start instead of being skipped.
 */
public class DebeziumEngineRunner implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(DebeziumEngineRunner.class);

	private final CdcProperties properties;

	private final MemberChangeHandler memberChangeHandler;

	private final ExecutorService executor = Executors
		.newSingleThreadExecutor(runnable -> new Thread(runnable, "debezium-member-cdc"));

	private DebeziumEngine<ChangeEvent<String, String>> engine;

	private volatile boolean running;

	public DebeziumEngineRunner(CdcProperties properties, MemberChangeHandler memberChangeHandler) {
		this.properties = properties;
		this.memberChangeHandler = memberChangeHandler;
	}

	@Override
	public void start() {
		createStateDirectory();
		engine = DebeziumEngine.create(Json.class)
			.using(engineProperties())
			.notifying(this::handleBatch)
			.using((success, message, error) -> {
				running = false;
				if (!success) {
					log.error("CDC engine stopped: {}", message, error);
				}
			})
			.build();
		running = true;
		executor.execute(engine);
		log.info("CDC started: {}.{} on {}:{}", properties.mysql().database(), properties.mysql().table(),
				properties.mysql().host(), properties.mysql().port());
	}

	@Override
	public void stop() {
		try {
			engine.close();
			executor.shutdown();
			executor.awaitTermination(30, TimeUnit.SECONDS);
		}
		catch (IOException e) {
			log.warn("CDC engine did not close cleanly", e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		finally {
			running = false;
		}
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	private void handleBatch(List<ChangeEvent<String, String>> records,
			RecordCommitter<ChangeEvent<String, String>> committer) throws InterruptedException {
		for (ChangeEvent<String, String> record : records) {
			memberChangeHandler.handle(record.value());
			committer.markProcessed(record);
		}
		committer.markBatchFinished();
	}

	private Properties engineProperties() {
		CdcProperties.Mysql mysql = properties.mysql();
		Properties config = new Properties();
		config.setProperty("name", "kitchensink-member-cdc");
		config.setProperty("connector.class", "io.debezium.connector.mysql.MySqlConnector");
		config.setProperty("topic.prefix", "kitchensink-legacy");

		config.setProperty("database.hostname", mysql.host());
		config.setProperty("database.port", String.valueOf(mysql.port()));
		config.setProperty("database.user", mysql.user());
		config.setProperty("database.password", mysql.password());
		config.setProperty("database.server.id", String.valueOf(mysql.serverId()));
		config.setProperty("database.include.list", mysql.database());
		config.setProperty("table.include.list", mysql.database() + "." + mysql.table());

		// first start copies existing rows, then the binlog is streamed; no global read lock on the shared server
		config.setProperty("snapshot.mode", "initial");
		config.setProperty("snapshot.locking.mode", "none");

		// binlog position and table structure survive restarts
		config.setProperty("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore");
		config.setProperty("offset.storage.file.filename", properties.stateDirectory().resolve("offsets.dat").toString());
		config.setProperty("offset.flush.interval.ms", "1000");
		config.setProperty("schema.history.internal", "io.debezium.storage.file.history.FileSchemaHistory");
		config.setProperty("schema.history.internal.file.filename",
				properties.stateDirectory().resolve("schema-history.dat").toString());
		config.setProperty("schema.history.internal.store.only.captured.databases.ddl", "true");
		config.setProperty("schema.history.internal.store.only.captured.tables.ddl", "true");

		// plain JSON events {"before", "after", "op", ...}; no extra empty event after a delete
		config.setProperty("converter.schemas.enable", "false");
		config.setProperty("tombstones.on.delete", "false");
		return config;
	}

	private void createStateDirectory() {
		try {
			Files.createDirectories(properties.stateDirectory());
		}
		catch (IOException e) {
			throw new UncheckedIOException("Cannot create CDC state directory " + properties.stateDirectory(), e);
		}
	}

}
