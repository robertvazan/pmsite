// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite.preferences;

public class ChainedPreferences extends PreferenceStorage {
	private final PreferenceStorage[] chain;
	public ChainedPreferences(PreferenceStorage... chain) {
		this.chain = chain;
	}
	@Override public String get(String key) {
		for (PreferenceStorage link : chain) {
			String value = link.get(key);
			if (value != null)
				return value;
		}
		return null;
	}
	@Override public void set(String key, String value) {
		for (PreferenceStorage link : chain)
			link.set(key, value);
	}
}
