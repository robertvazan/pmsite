// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite.preferences;

import java.util.*;
import com.machinezoo.noexception.*;

public class EnumPreference<T extends Enum<T>> extends PreferenceField<T> {
	private final Class<T> clazz;
	public Class<T> clazz() {
		return clazz;
	}
	private final T fallback;
	public EnumPreference(PreferenceKey key, Class<T> clazz, T fallback) {
		super(key);
		Objects.requireNonNull(fallback);
		this.clazz = clazz;
		this.fallback = fallback;
	}
	public T get() {
		String raw = key.get();
		return raw != null ? Exceptions.log().get(() -> Enum.valueOf(clazz, raw)).orElse(fallback) : fallback;
	}
	public void set(T value) {
		key.set(value != null ? value.name() : fallback.name());
	}
	@Override public T genericGet() {
		return get();
	}
	@Override public void genericSet(T value) {
		set(value);
	}
}
