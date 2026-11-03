package com.migration.kitchensink.infrastructure.cdc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.migration.kitchensink.domaine.models.Member;
import com.migration.kitchensink.infrastructure.database.MemberChange;
import com.migration.kitchensink.infrastructure.database.MemberPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;

/**
 * Applies Debezium change events of the legacy Member table to MongoDB, one batch at a time. Writes straight through
 * the persistence layer: copying legacy data is a migration concern, not a domain operation.
 *
 * Event value (JSON, without schema):
 * {"before": {...} | null, "after": {...} | null, "op": "c" | "u" | "d" | "r"}
 * with rows shaped like the MySQL table: {"id", "email", "name", "phone_number"}.
 */
@Component
public class MemberChangeHandler {

	private static final Logger log = LoggerFactory.getLogger(MemberChangeHandler.class);

	private final MemberPersistence memberPersistence;

	private final JsonMapper jsonMapper;

	public MemberChangeHandler(MemberPersistence memberPersistence, JsonMapper jsonMapper) {
		this.memberPersistence = memberPersistence;
		this.jsonMapper = jsonMapper;
	}

	/**
	 * Applies a batch of change events, in their order, with one write.
	 * @param eventValues the change event values; {@code null} for tombstones, which are ignored
	 */
	public void handle(List<String> eventValues) {
		List<MemberChange> changes = new ArrayList<>(eventValues.size());
		for (String eventValue : eventValues) {
			toChange(eventValue).ifPresent(changes::add);
		}
		if (!changes.isEmpty()) {
			memberPersistence.synchronize(changes);
		}
	}

	private Optional<MemberChange> toChange(String eventValue) {
		if (eventValue == null) {
			return Optional.empty();
		}
		JsonNode event = jsonMapper.readTree(eventValue);
		String op = event.path("op").asString();
		return switch (op) {
			case "c", "u", "r" -> Optional.of(new MemberChange.Upsert(toMember(event.path("after"))));
			case "d" -> Optional.of(new MemberChange.Removal(event.path("before").path("id").asLong()));
			default -> {
				log.warn("Ignoring Member change event with unsupported operation '{}'", op);
				yield Optional.empty();
			}
		};
	}

	private static Member toMember(JsonNode row) {
		return new Member(row.path("id").asLong(), row.path("name").asString(), row.path("email").asString(),
				row.path("phone_number").asString());
	}

}
