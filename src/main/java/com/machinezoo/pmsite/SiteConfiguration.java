// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import java.net.*;

public abstract class SiteConfiguration {
	public abstract URI uri();
	protected final SiteMappings mappings = new SiteMappings().contentRoot(getClass());
	public SiteMappings mappings() {
		return mappings;
	}
	protected final SiteResources resources = new SiteResources(mappings).root(getClass());
}
