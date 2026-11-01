package com.migration.kitchensink.infrastructure.web;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

import com.migration.kitchensink.domaine.exceptions.EmailAlreadyTakenException;
import com.migration.kitchensink.domaine.exceptions.MemberValidationException;
import com.migration.kitchensink.domaine.models.Member;
import com.migration.kitchensink.domaine.models.NewMember;
import com.migration.kitchensink.domaine.services.MemberService;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The members page, a copy of the legacy JSF page (index.xhtml): same URL, same HTML, same form field names (reg:*),
 * so the gateway can send a user to either system and a form rendered by legacy is also accepted here.
 *
 * Stateless: unlike JSF, nothing is kept between showing the page and submitting it. Registration goes through the
 * same domain service as the REST API.
 */
@Controller
public class MemberController {

	// like legacy, the page returned after a registration lists the new member: wait for CDC to copy it to MongoDB
	static final Duration NEW_MEMBER_VISIBLE_TIMEOUT = Duration.ofSeconds(2);

	// legacy has no email check before saving: its page shows the MySQL duplicate-key error as is (kept during the
	// migration); the key name is the one Hibernate generated for the unique email constraint
	static final String DUPLICATE_EMAIL_ERROR = "Duplicate entry '%s' for key 'Member.UK9qv6yhjqm8iafto8qk452gx8h'";

	private static final String PAGE = "index";

	private final MemberService memberService;

	public MemberController(MemberService memberService) {
		this.memberService = memberService;
	}

	@GetMapping("/index.jsf")
	public String page(Model model) {
		return render(model, RegistrationForm.empty(), Map.of(), false, "");
	}

	// legacy-rendered forms also send jakarta.faces.ViewState and other hidden fields: ignored
	@PostMapping("/index.jsf")
	public String register(@RequestParam(name = "reg:name", defaultValue = "") String name,
			@RequestParam(name = "reg:email", defaultValue = "") String email,
			@RequestParam(name = "reg:phoneNumber", defaultValue = "") String phoneNumber, Locale locale, Model model) {
		RegistrationForm form = new RegistrationForm(name, email, phoneNumber);
		try {
			Member member = memberService.register(new NewMember(null, name, email, phoneNumber), locale);
			memberService.awaitReadable(member.id(), NEW_MEMBER_VISIBLE_TIMEOUT);
			return render(model, RegistrationForm.empty(), Map.of(), true, "");
		}
		catch (MemberValidationException exception) {
			return render(model, form, exception.violations(), false, "");
		}
		catch (EmailAlreadyTakenException exception) {
			return render(model, form, Map.of(), false, DUPLICATE_EMAIL_ERROR.formatted(email));
		}
	}

	private String render(Model model, RegistrationForm form, Map<String, String> fieldErrors, boolean registered,
			String errorMessage) {
		model.addAttribute("form", form);
		model.addAttribute("fieldErrors", fieldErrors);
		model.addAttribute("registered", registered);
		model.addAttribute("errorMessage", errorMessage);
		model.addAttribute("members", memberService.findAllOrderedByName().stream().map(MemberRow::from).toList());
		return PAGE;
	}

}
