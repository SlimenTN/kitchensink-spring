package com.migration.kitchensink.infrastructure.cdc;

import java.util.Arrays;
import java.util.List;

import com.migration.kitchensink.domaine.models.Member;
import com.migration.kitchensink.domaine.models.MemberChange;
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

	private static final String JOHN = """
			{"id": 2, "email": "john@example.com", "name": "John Smith", "phone_number": "2125551212"}""";

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
		handler.handle(List.of(event(op, null, JANE)));

		then(memberService).should()
			.synchronize(List.of(upsert(1L, "Jane Doe", "jane.doe@example.com", "2125551234")));
	}

	@Test
	void updatedRowIsSynchronizedWithItsNewValues() {
		handler.handle(List.of(event("u", JANE, JANE_UPDATED)));

		then(memberService).should().synchronize(List.of(upsert(1L, "Jane Smith", "jane@example.org", "2125550000")));
	}

	@Test
	void deletedRowIsRemoved() {
		handler.handle(List.of(event("d", JANE, null)));

		then(memberService).should().synchronize(List.of(new MemberChange.Removal(1L)));
	}

	// one write for the whole batch, in the binlog order
	@Test
	void aBatchIsSynchronizedInOneCallKeepingTheEventOrder() {
		handler.handle(List.of(event("r", null, JOHN), event("c", null, JANE), event("u", JANE, JANE_UPDATED),
				event("d", JANE_UPDATED, null)));

		then(memberService).should()
			.synchronize(List.of(upsert(2L, "John Smith", "john@example.com", "2125551212"),
					upsert(1L, "Jane Doe", "jane.doe@example.com", "2125551234"),
					upsert(1L, "Jane Smith", "jane@example.org", "2125550000"), new MemberChange.Removal(1L)));
	}

	@Test
	void tombstonesAndUnsupportedOperationsAreSkipped() {
		handler.handle(Arrays.asList(null, event("t", null, null), event("c", null, JANE)));

		then(memberService).should()
			.synchronize(List.of(upsert(1L, "Jane Doe", "jane.doe@example.com", "2125551234")));
	}

	@Test
	void aBatchWithNothingToApplyWritesNothing() {
		handler.handle(Arrays.asList((String) null));

		verifyNoInteractions(memberService);
	}

	private static MemberChange upsert(long id, String name, String email, String phoneNumber) {
		return new MemberChange.Upsert(new Member(id, name, email, phoneNumber));
	}

	private static String event(String op, String before, String after) {
		return """
				{"before": %s, "after": %s, "op": "%s", "source": {"db": "kitchensink", "table": "Member"}}"""
			.formatted(before, after, op);
	}

}
