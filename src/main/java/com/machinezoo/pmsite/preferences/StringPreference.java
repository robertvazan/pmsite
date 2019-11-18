// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite.preferences;

import java.util.*;
import java.util.function.*;
import java.util.regex.*;
import com.google.common.cache.*;

public class StringPreference extends PreferenceField<String> {
	private static LoadingCache<String, Predicate<String>> patterns = CacheBuilder.newBuilder().build(CacheLoader.from(formatted -> {
		Pattern pattern = Pattern.compile(formatted);
		return value -> pattern.matcher(value).matches();
	}));
	private final String fallback;
	public String fallback() {
		return fallback;
	}
	private final Predicate<String> validator;
	public StringPreference(PreferenceKey key, String fallback, Predicate<String> validator) {
		super(key);
		Objects.requireNonNull(fallback);
		this.fallback = fallback;
		this.validator = validator;
	}
	public StringPreference(PreferenceKey key, String fallback, String pattern) {
		this(key, fallback, pattern != null ? patterns.getUnchecked(pattern) : null);
	}
	public StringPreference(PreferenceKey key, String fallback) {
		this(key, fallback, (Predicate<String>)null);
	}
	public String get() {
		String raw = key.get();
		return valid(raw) ? raw : fallback;
	}
	public void set(String value) {
		key.set(valid(value) ? value : fallback);
	}
	@Override public String genericGet() {
		return get();
	}
	@Override public void genericSet(String value) {
		set(value);
	}
	@Override public String genericFallback() {
		return fallback;
	}
	private boolean valid(String value) {
		return value != null && (validator == null || validator.test(value));
	}
}
