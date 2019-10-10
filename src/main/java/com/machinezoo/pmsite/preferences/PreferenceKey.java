// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite.preferences;

import java.util.*;
import java.util.function.*;

public class PreferenceKey {
	private final PreferenceStorage storage;
	private final String slug;
	public PreferenceKey(PreferenceStorage storage, String slug) {
		Objects.requireNonNull(storage);
		this.storage = storage;
		this.slug = PreferenceGroup.escape(slug);
	}
	public String get() {
		if (slug == null)
			return null;
		return storage.get(slug);
	}
	public void set(String value) {
		if (slug != null)
			storage.set(slug, value);
	}
	public StringPreference asString(String fallback) {
		return new StringPreference(this, fallback);
	}
	public StringPreference asString(Predicate<String> validator, String fallback) {
		return new StringPreference(this, fallback, validator);
	}
	public StringPreference asString(String regex, String fallback) {
		return new StringPreference(this, fallback, regex);
	}
	public IntPreference asInt(int fallback) {
		return new IntPreference(this, fallback);
	}
	public BooleanPreference asBoolean(boolean fallback) {
		return new BooleanPreference(this, fallback);
	}
	public <T extends Enum<T>> EnumPreference<T> asEnum(Class<T> clazz, T fallback) {
		return new EnumPreference<>(this, clazz, fallback);
	}
	@SuppressWarnings("unchecked") public <T extends Enum<T>> EnumPreference<T> asEnum(T fallback) {
		return new EnumPreference<>(this, (Class<T>)fallback.getClass(), fallback);
	}
}
