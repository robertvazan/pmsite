// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite.preferences;

import com.machinezoo.noexception.*;

public class IntPreference extends PreferenceField<Integer> {
	private final int fallback;
	public int fallback() {
		return fallback;
	}
	public IntPreference(PreferenceKey key, int fallback) {
		super(key);
		this.fallback = fallback;
	}
	public int get() {
		String raw = key.get();
		return raw != null ? Exceptions.log().getAsInt(() -> Integer.parseInt(raw)).orElse(fallback) : fallback;
	}
	public void set(int value) {
		key.set(Integer.toString(value));
	}
	@Override public Integer genericGet() {
		return get();
	}
	@Override public void genericSet(Integer value) {
		set(value != null ? value : fallback);
	}
	@Override public Integer genericFallback() {
		return fallback;
	}
}
