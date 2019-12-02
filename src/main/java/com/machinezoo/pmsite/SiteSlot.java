// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite;

import java.util.function.*;
import com.machinezoo.pmsite.preferences.*;

/*
 * Provides hierarchical ID to widgets.
 * 
 * Likely needs redesign:
 * - tolerate arbitrary text, not just identifier
 * - accept name options like encrypted or hashed
 * - expose raw name chain along with options
 * - expose a number of standard IDs: HTML ID attribute, analytics event ID, URL-safe, filename-safe
 * - preferences, analytics, and temporary storage should be page-local rather than slot-local
 */
public abstract class SiteSlot {
	public abstract SitePage page();
	public abstract String id();
	public abstract PreferenceStorage preferences();
	public abstract <T> T local(String name, Supplier<T> initializer);
	public abstract SiteAnalytics analytics();
	public SiteSlot nested(String name) {
		return new NestedViewState(this, name);
	}
	private static class NestedViewState extends SiteSlot {
		final SiteSlot parent;
		final String name;
		NestedViewState(SiteSlot parent, String name) {
			this.parent = parent;
			this.name = name;
		}
		@Override public SitePage page() {
			return parent.page();
		}
		@Override public String id() {
			return parent.id() + "-" + name;
		}
		@Override public PreferenceStorage preferences() {
			return parent.preferences().group(name);
		}
		@Override public <T> T local(String key, Supplier<T> initializer) {
			return parent.local(name + "." + key, initializer);
		}
		@Override public SiteAnalytics analytics() {
			return parent.analytics();
		}
	}
}
