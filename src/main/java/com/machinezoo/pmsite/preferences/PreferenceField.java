// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite.preferences;

public abstract class PreferenceField<T> {
	protected final PreferenceKey key;
	public abstract T genericGet();
	public abstract void genericSet(T value);
	public abstract T genericFallback();
	protected PreferenceField(PreferenceKey key) {
		this.key = key;
	}
}
