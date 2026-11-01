package com.migration.kitchensink.infrastructure.web;

import java.util.List;
import java.util.Locale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

// Page language from the browser (Accept-Language), with the legacy page's languages (faces-config.xml): default en
@Configuration(proxyBeanMethods = false)
public class WebConfiguration {

	@Bean
	LocaleResolver localeResolver() {
		AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
		resolver.setSupportedLocales(List.of(Locale.ENGLISH, Locale.US, Locale.FRENCH, Locale.FRANCE,
				Locale.of("es"), Locale.of("es", "ES")));
		resolver.setDefaultLocale(Locale.ENGLISH);
		return resolver;
	}

}
