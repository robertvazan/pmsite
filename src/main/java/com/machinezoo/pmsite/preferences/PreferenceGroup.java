// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite.preferences;

import java.util.*;

public class PreferenceGroup extends PreferenceStorage {
	private final PreferenceStorage storage;
	private final String slug;
	public PreferenceGroup(PreferenceStorage storage, String slug) {
		Objects.requireNonNull(storage);
		this.storage = storage;
		this.slug = escape(slug);
	}
	@Override public String get(String key) {
		if (slug == null || key == null)
			return null;
		return storage.get(slug + "." + key);
	}
	@Override public void set(String key, String value) {
		if (slug != null && key != null)
			storage.set(slug + "." + key, value);
	}
	public static String escape(String slug) {
		if (slug == null)
			return null;
		if (slug.indexOf('~') >= 0 || slug.indexOf('.') >= 0)
			return slug.replaceAll("([~.])", "~$1");
		return slug;
	}
}
