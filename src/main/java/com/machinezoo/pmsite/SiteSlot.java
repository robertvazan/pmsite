// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import java.util.function.*;
import com.machinezoo.pmsite.preferences.*;

public abstract class SiteSlot {
	public abstract String id();
	public abstract SitePreferences preferences();
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
		@Override public String id() {
			return parent.id() + "-" + name;
		}
		@Override public SitePreferences preferences() {
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
