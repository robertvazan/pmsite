// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite.preferences;

import java.util.*;
import com.machinezoo.hookless.*;

public class PinnedPreferences extends PreferenceStorage {
	private final PreferenceStorage inner;
	public PinnedPreferences(PreferenceStorage inner) {
		this.inner = inner;
	}
	@Override public String get(String key) {
		return CurrentReactiveScope.freeze(new PinnedKey(key), () -> inner.get(key));
	}
	@Override public void set(String key, String value) {
		inner.set(key, value);
	}
	private static class PinnedKey {
		final String key;
		PinnedKey(String key) {
			this.key = key;
		}
		@Override public boolean equals(Object obj) {
			return obj instanceof PinnedKey && Objects.equals(key, ((PinnedKey)obj).key);
		}
		@Override public int hashCode() {
			return Objects.hashCode(key);
		}
	}
}
