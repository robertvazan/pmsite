// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import java.net.*;
import com.machinezoo.pushmode.*;

public abstract class SiteConfiguration {
	public abstract URI uri();
	protected final SiteMappings mappings = new SiteMappings().contentRoot(getClass());
	public SiteMappings mappings() {
		return mappings;
	}
	protected final SiteResources resources = new SiteResources(mappings).root(getClass());
	public String asset(String path) {
		if (path.startsWith("http"))
			return path;
		String hash = resources.hash(path);
		String buster = hash != null ? "?v=" + hash : SiteReload.buster();
		return path + buster;
	}
	public SiteConfiguration() {
		mappings
			.map("/pushmode/poller", new PollServlet())
			.map("/pushmode/submit", new SubmitServlet())
			.map("/pushmode/script", new PushScriptServlet());
	}
}
