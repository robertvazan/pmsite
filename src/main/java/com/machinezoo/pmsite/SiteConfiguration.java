// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.function.*;
import com.machinezoo.hookless.servlets.*;
import com.machinezoo.pushmode.*;
import cz.jiripinkas.jsitemapgenerator.generator.*;

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
	public SitemapGenerator sitemap() {
		return SitemapGenerator.of(uri().toString());
	}
	public SiteConfiguration() {
		mappings
			.map("/pushmode/poller", new PollServlet())
			.map("/pushmode/submit", new SubmitServlet())
			.map("/pushmode/script", new PushScriptServlet())
			.map("/sitemap.xml", new SitemapServlet(this::sitemap));
	}
	@SuppressWarnings("serial") private static class SitemapServlet extends ReactiveServlet {
		final Supplier<SitemapGenerator> supplier;
		SitemapServlet(Supplier<SitemapGenerator> supplier) {
			this.supplier = supplier;
		}
		@Override public ReactiveServletResponse doGet(ReactiveServletRequest request) {
			SitemapGenerator sitemap = supplier.get();
			byte[] data = sitemap.toString().getBytes(StandardCharsets.UTF_8);
			ReactiveServletResponse response = new ReactiveServletResponse();
			response.headers().put("Content-Type", "text/xml; charset=utf-8");
			response.headers().put("Content-Length", Integer.toString(data.length));
			response.headers().put("Cache-Control", "public, max-age=86400");
			response.data(ByteBuffer.wrap(data));
			return response;
		}
	}
}
