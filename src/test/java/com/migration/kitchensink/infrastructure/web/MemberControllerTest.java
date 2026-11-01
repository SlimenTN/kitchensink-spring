package com.migration.kitchensink.infrastructure.web;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.migration.kitchensink.domaine.exceptions.EmailAlreadyTakenException;
import com.migration.kitchensink.domaine.exceptions.MemberValidationException;
import com.migration.kitchensink.domaine.models.Member;
import com.migration.kitchensink.domaine.models.NewMember;
import com.migration.kitchensink.domaine.services.MemberService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The members page, against the HTML of the legacy JSF page (texts, field names, message markup captured from the
 * running legacy app). The full page is compared with legacy by the parity tests.
 */
@WebMvcTest(MemberController.class)
@Import(WebConfiguration.class)
class MemberControllerTest {

	private static final Member JOHN = new Member(0L, "John Smith", "john.smith@mailinator.com", "2125551212");

	private static final Member JANE = new Member(7L, "Jane Doe", "jane.doe@example.com", "2125551234");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MemberService memberService;

	@BeforeEach
	void members() {
		given(memberService.findAllOrderedByName()).willReturn(List.of(JANE, JOHN));
	}

	@Test
	void pageShowsTheRegistrationFormAndTheMembers() throws Exception {
		Document page = page(get("/index.jsf"), "en");

		assertThat(page.title()).isEqualTo("kitchensink");
		assertThat(page.select("h1").text()).isEqualTo("Welcome to JBoss!");
		assertThat(page.selectFirst("link[rel=stylesheet]").attr("href")).isEqualTo("/kitchensink/resources/css/screen.css");
		assertThat(page.selectFirst("form#reg").attr("action")).isEqualTo("/kitchensink/index.jsf");
		assertThat(page.selectFirst("form#reg").attr("method")).isEqualTo("post");
		assertThat(formValues(page)).containsExactly("", "", "");
		assertThat(page.select("input#reg\\:register").attr("value")).isEqualTo("Register");
		// rows in service order; the last column links to the REST API behind the gateway
		assertThat(page.select("table.simpletablestyle tbody tr")).extracting(row -> row.select("td").eachText())
			.containsExactly(
					List.of("7", "Jane Doe", "jane.doe@example.com", "2125551234", "/rest/members/7"),
					List.of("0", "John Smith", "john.smith@mailinator.com", "2125551212", "/rest/members/0"));
		assertThat(page.select("table.simpletablestyle tbody a").eachAttr("href"))
			.containsExactly("/kitchensink/rest/members/7", "/kitchensink/rest/members/0");
		assertThat(page.select("ul.messages")).isEmpty();
	}

	@Test
	void pageWithoutMembersShowsTheLegacyText() throws Exception {
		given(memberService.findAllOrderedByName()).willReturn(List.of());

		Document page = page(get("/index.jsf"), "fr");

		assertThat(page.select("table.simpletablestyle")).isEmpty();
		// hard-coded in English in the legacy page, whatever the language
		assertThat(page.select("em").text()).isEqualTo("No registered members.");
	}

	// texts from the legacy bundles (Resources.properties, _fr, _es); unknown languages fall back to English
	@ParameterizedTest(name = "{0}")
	@CsvSource({ "en, Welcome to JBoss!, Name:, Register", "fr, Bienvenue sur JBoss!, Nom :, Enregistrer",
			"es, Bienvenido a JBoss!, Nombre:, Registrar", "de, Welcome to JBoss!, Name:, Register" })
	void pageIsInTheBrowserLanguage(String language, String heading, String nameLabel, String button) throws Exception {
		Document page = page(get("/index.jsf"), language);

		assertThat(page.select("h1").text()).isEqualTo(heading);
		assertThat(page.select("label[for=reg:name]").text()).isEqualTo(nameLabel);
		assertThat(page.select("input#reg\\:register").attr("value")).isEqualTo(button);
	}

	@Test
	void validMemberIsRegisteredAndTheFormIsEmptied() throws Exception {
		NewMember jane = new NewMember(null, "Jane Doe", "jane.doe@example.com", "2125551234");
		given(memberService.register(jane, Locale.ENGLISH)).willReturn(JANE);

		Document page = page(form("Jane Doe", "jane.doe@example.com", "2125551234"), "en");

		assertThat(page.select("ul.messages li.valid").text()).isEqualTo("Registered!");
		assertThat(formValues(page)).containsExactly("", "", "");
		// the returned page lists the new member, like legacy: wait for CDC to copy it to MongoDB
		then(memberService).should().awaitReadable(7L, MemberController.NEW_MEMBER_VISIBLE_TIMEOUT);
		assertThat(page.select("table.simpletablestyle tbody").text()).contains("jane.doe@example.com");
	}

	@Test
	void registrationMessageIsInTheBrowserLanguage() throws Exception {
		given(memberService.register(any(), any())).willReturn(JANE);

		Document page = page(form("Jane Doe", "jane.doe@example.com", "2125551234"), "fr");

		assertThat(page.select("ul.messages li.valid").text()).isEqualTo("Enregistré!");
		then(memberService).should().register(new NewMember(null, "Jane Doe", "jane.doe@example.com", "2125551234"),
				Locale.FRENCH);
	}

	@Test
	void invalidMemberShowsEachMessageNextToItsFieldAndKeepsTheValues() throws Exception {
		given(memberService.register(any(), any())).willThrow(new MemberValidationException(
				Map.of("name", "must contain only letters and spaces", "phoneNumber", "size must be between 10 and 12")));

		Document page = page(form("Bad 123", "jane.doe@example.com", "12"), "en");

		assertThat(formValues(page)).containsExactly("Bad 123", "jane.doe@example.com", "12");
		assertThat(fieldError(page, "name")).isEqualTo("must contain only letters and spaces");
		assertThat(fieldError(page, "email")).isEmpty();
		assertThat(fieldError(page, "phoneNumber")).isEqualTo("size must be between 10 and 12");
		assertThat(page.select("ul.messages")).isEmpty();
		then(memberService).should(never()).awaitReadable(anyLong(), any());
	}

	// empty fields are sent as "" (like JSF), not missing: the legacy messages are about sizes, not nulls
	@Test
	void emptyFieldsAreRegisteredAsEmptyTexts() throws Exception {
		given(memberService.register(any(), any())).willThrow(new MemberValidationException(Map.of("name", "x")));

		page(form("", "", ""), "en");

		then(memberService).should().register(new NewMember(null, "", "", ""), Locale.ENGLISH);
	}

	@Test
	void duplicateEmailShowsTheLegacyDatabaseMessage() throws Exception {
		given(memberService.register(any(), any()))
			.willThrow(new EmailAlreadyTakenException("john.smith@mailinator.com"));

		Document page = page(form("Someone Else", "john.smith@mailinator.com", "2125550601"), "en");

		// legacy shows the MySQL duplicate-key error as is; kept identical during the migration
		assertThat(page.select("ul.messages li.invalid").text())
			.isEqualTo("Duplicate entry 'john.smith@mailinator.com' for key 'Member.UK9qv6yhjqm8iafto8qk452gx8h'");
		assertThat(formValues(page)).containsExactly("Someone Else", "john.smith@mailinator.com", "2125550601");
	}

	// a form rendered by legacy posts to index.jsf;jsessionid=... with JSF hidden fields: accepted, extras ignored
	@Test
	void formRenderedByTheLegacyPageIsAccepted() throws Exception {
		given(memberService.register(any(), any())).willReturn(JANE);

		mockMvc.perform(post("/index.jsf;jsessionid=1fGHNeNxw4rWEbc").header(HttpHeaders.ACCEPT_LANGUAGE, "en")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.param("reg", "reg")
			.param("reg:name", "Jane Doe")
			.param("reg:email", "jane.doe@example.com")
			.param("reg:phoneNumber", "2125551234")
			.param("reg:register", "Register")
			.param("jakarta.faces.ViewState", "-1234567890:987654321"))
			.andExpect(status().isOk());

		then(memberService).should().register(new NewMember(null, "Jane Doe", "jane.doe@example.com", "2125551234"),
				Locale.ENGLISH);
	}

	@Test
	void stylesheetAndImagesAreServedLikeLegacy() throws Exception {
		mockMvc.perform(get("/resources/css/screen.css"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith("text/css"))
			// legacy's #{request.contextPath} resolved to the public path behind the gateway
			.andExpect(content().string(org.hamcrest.Matchers.containsString("/kitchensink/resources/gfx/headerbkg.png")));
		for (String image : List.of("rhjb_eap_logo.png", "headerbkg.png", "bkg-blkheader.png", "asidebkg.png")) {
			mockMvc.perform(get("/resources/gfx/" + image))
				.andExpect(status().isOk())
				.andExpect(content().contentType(MediaType.IMAGE_PNG));
		}
	}

	// "/" serves legacy's index.html (Spring forwards to it; MockMvc does not follow forwards, so both steps are checked)
	@Test
	void rootRedirectsToThePageLikeLegacy() throws Exception {
		mockMvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
		mockMvc.perform(get("/index.html"))
			.andExpect(status().isOk())
			.andExpect(content().string(org.hamcrest.Matchers.containsString("URL=index.jsf")));
	}

	private Document page(MockHttpServletRequestBuilder request, String language) throws Exception {
		String html = mockMvc.perform(request.header(HttpHeaders.ACCEPT_LANGUAGE, language))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return Jsoup.parse(html);
	}

	private static MockHttpServletRequestBuilder form(String name, String email, String phoneNumber) {
		return post("/index.jsf").contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.param("reg", "reg")
			.param("reg:name", name)
			.param("reg:email", email)
			.param("reg:phoneNumber", phoneNumber)
			.param("reg:register", "Register");
	}

	private static List<String> formValues(Document page) {
		return List.of(page.select("input#reg\\:name").val(), page.select("input#reg\\:email").val(),
				page.select("input#reg\\:phoneNumber").val());
	}

	// legacy shows a field's message in the third cell of its row
	private static String fieldError(Document page, String field) {
		return page.select("input#reg\\:" + field).parents().select("tr").first().select("td").get(2).text();
	}

}
