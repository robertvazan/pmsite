// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite.preferences;

import com.machinezoo.noexception.*;

public class BooleanPreference extends PreferenceField<Boolean> {
	private final boolean fallback;
	public BooleanPreference(PreferenceKey key, boolean fallback) {
		super(key);
		this.fallback = fallback;
	}
	public boolean get() {
		String raw = key.get();
		return raw != null ? Exceptions.log().getAsBoolean(() -> Boolean.parseBoolean(raw)).orElse(fallback) : fallback;
	}
	public void set(boolean value) {
		key.set(Boolean.toString(value));
	}
	@Override public Boolean genericGet() {
		return get();
	}
	@Override public void genericSet(Boolean value) {
		set(value != null ? value : fallback);
	}
}