// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite.preferences;

public abstract class PreferenceStorage {
	public abstract String get(String key);
	public abstract void set(String key, String value);
	public PreferenceGroup group(String slug) {
		return new PreferenceGroup(this, slug);
	}
	public PreferenceGroup group(Class<?> clazz) {
		return new PreferenceGroup(this, clazz.getCanonicalName().replace('.', '-'));
	}
	public PreferenceKey key(String slug) {
		return new PreferenceKey(this, slug);
	}
}
