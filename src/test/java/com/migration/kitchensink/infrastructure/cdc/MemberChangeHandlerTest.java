package com.migration.kitchensink.infrastructure.cdc;

import com.migration.kitchensink.domaine.models.Member;
import com.migration.kitchensink.domaine.services.MemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class MemberChangeHandlerTest {

	private static final String JANE = """
			{"id": 1, "email": "jane.doe@example.com", "name": "Jane Doe", "phone_number": "2125551234"}""";

	private static final String JANE_UPDATED = """
			{"id": 1, "email": "jane@example.org", "name": "Jane Smith", "phone_number": "2125550000"}""";

	@Mock
	private MemberService memberService;

	private MemberChangeHandler handler;

	@BeforeEach
	void setUp() {
		handler = new MemberChangeHandler(memberService, JsonMapper.builder().build());
	}

	// c = insert, r = row read during the initial snapshot
	@ParameterizedTest
	@ValueSource(strings = { "c", "r" })
	void createdOrSnapshotRowIsSynchronized(String op) {
		handler.handle(event(op, null, JANE));

		then(memberService).should().synchronize(new Member(1L, "Jane Doe", "jane.doe@example.com", "2125551234"));
	}

	@Test
	void updatedRowIsSynchronizedWithItsNewValues() {
		handler.handle(event("u", JANE, JANE_UPDATED));

		then(memberService).should().synchronize(new Member(1L, "Jane Smith", "jane@example.org", "2125550000"));
	}

	@Test
	void deletedRowIsRemoved() {
		handler.handle(event("d", JANE, null));

		then(memberService).should().remove(1L);
	}

	@Test
	void tombstoneIsIgnored() {
		handler.handle(null);

		verifyNoInteractions(memberService);
	}

	private static String event(String op, String before, String after) {
		return """
				{"before": %s, "after": %s, "op": "%s", "source": {"db": "kitchensink", "table": "Member"}}"""
			.formatted(before, after, op);
	}

}
