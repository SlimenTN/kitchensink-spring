package com.migration.kitchensink.infrastructure.cdc;

import com.migration.kitchensink.domaine.models.Member;
import com.migration.kitchensink.domaine.services.MemberService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;

/**
 * Applies one Debezium change event of the legacy Member table to the domain.
 *
 * Event value (JSON, without schema):
 * {"before": {...} | null, "after": {...} | null, "op": "c" | "u" | "d" | "r"}
 * with rows shaped like the MySQL table: {"id", "email", "name", "phone_number"}.
 */
@Component
public class MemberChangeHandler {

	private static final Logger log = LoggerFactory.getLogger(MemberChangeHandler.class);

	private final MemberService memberService;

	private final JsonMapper jsonMapper;

	public MemberChangeHandler(MemberService memberService, JsonMapper jsonMapper) {
		this.memberService = memberService;
		this.jsonMapper = jsonMapper;
	}

	/**
	 * @param eventValue the change event value; {@code null} for tombstones, which are ignored
	 */
	public void handle(String eventValue) {
		if (eventValue == null) {
			return;
		}
		JsonNode event = jsonMapper.readTree(eventValue);
		String op = event.path("op").asString();
		switch (op) {
			case "c", "u", "r" -> memberService.synchronize(toMember(event.path("after")));
			case "d" -> memberService.remove(event.path("before").path("id").asLong());
			default -> log.warn("Ignoring Member change event with unsupported operation '{}'", op);
		}
	}

	private static Member toMember(JsonNode row) {
		return new Member(row.path("id").asLong(), row.path("name").asString(), row.path("email").asString(),
				row.path("phone_number").asString());
	}

}
