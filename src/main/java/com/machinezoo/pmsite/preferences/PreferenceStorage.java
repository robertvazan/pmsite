// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite.preferences;

import java.util.*;
import com.machinezoo.hookless.*;

public abstract class PreferenceStorage {
	public abstract String get(String key);
	public abstract void set(String key, String value);
	public static PreferenceStorage memory() {
		return new MemoryPreferences();
	}
	private static class MemoryPreferences extends PreferenceStorage {
		final Map<String, String> map = ReactiveCollections.map(new HashMap<>(), new ReactiveCollections.Options()
			.perItem()
			.compareValues()
			.ignoreWriteStatus()
			.ignoreWriteExceptions());
		@Override public synchronized String get(String key) {
			if (key == null)
				return null;
			return map.get(key);
		}
		@Override public synchronized void set(String key, String value) {
			if (key != null) {
				if (value != null)
					map.put(key, value);
				else
					map.remove(key);
			}
		}
	}
	public PreferenceStorage pinned() {
		return new PinnedPreferences(this);
	}
	private static class PinnedPreferences extends PreferenceStorage {
		private final PreferenceStorage inner;
		PinnedPreferences(PreferenceStorage inner) {
			this.inner = inner;
		}
		@Override public String get(String key) {
			return CurrentReactiveScope.freeze(new PinnedKey(key), () -> inner.get(key));
		}
		@Override public void set(String key, String value) {
			inner.set(key, value);
		}
		static class PinnedKey {
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
	public static PreferenceStorage chained(PreferenceStorage... chain) {
		return new ChainedPreferences(chain);
	}
	private static class ChainedPreferences extends PreferenceStorage {
		private final PreferenceStorage[] chain;
		ChainedPreferences(PreferenceStorage[] chain) {
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
	static String escape(String slug) {
		if (slug == null)
			return null;
		if (slug.indexOf('~') >= 0 || slug.indexOf('.') >= 0)
			return slug.replaceAll("([~.])", "~$1");
		return slug;
	}
	public PreferenceStorage group(String slug) {
		return new PreferenceGroup(this, slug);
	}
	public PreferenceStorage group(Class<?> clazz) {
		return new PreferenceGroup(this, clazz.getCanonicalName().replace('.', '-'));
	}
	private static class PreferenceGroup extends PreferenceStorage {
		final PreferenceStorage parent;
		final String slug;
		public PreferenceGroup(PreferenceStorage parent, String slug) {
			Objects.requireNonNull(parent);
			this.parent = parent;
			this.slug = escape(slug);
		}
		@Override public String get(String key) {
			if (slug == null || key == null)
				return null;
			return parent.get(slug + "." + key);
		}
		@Override public void set(String key, String value) {
			if (slug != null && key != null)
				parent.set(slug + "." + key, value);
		}
	}
	public PreferenceKey key(String slug) {
		return new PreferenceKey(this, slug);
	}
}
