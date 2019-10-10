// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite.preferences;

public abstract class PreferenceField<T> {
	protected final PreferenceKey key;
	public abstract T genericGet();
	public abstract void genericSet(T value);
	protected PreferenceField(PreferenceKey key) {
		this.key = key;
	}
}
