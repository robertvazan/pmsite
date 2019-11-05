// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.function.Supplier;
import java.util.stream.*;
import com.google.common.base.*;
import com.machinezoo.hookless.servlets.*;
import com.machinezoo.pushmode.*;
import cz.jiripinkas.jsitemapgenerator.*;
import cz.jiripinkas.jsitemapgenerator.WebPage.WebPageBuilder;
import cz.jiripinkas.jsitemapgenerator.generator.*;

public abstract class SiteConfiguration {
	private URI uri;
	public final URI uri() {
		return uri;
	}
	public SiteConfiguration uri(URI uri) {
		this.uri = uri;
		return this;
	}
	protected final SiteMappings mappings = new SiteMappings().contentRoot(getClass());
	public SiteMappings completeMappings() {
		/*
		 * Locations should be dynamically changing in the future.
		 * For now, we will just initialize them here just before registering them with servlet container.
		 * This code cannot be in constructor, because deriver class constructor should get a chance to run first.
		 */
		locations()
			.filter(l -> l.page() != null)
			.forEach(l -> {
				mappings.map(l.path(), () -> l.page().get().location(l));
				for (String alias : l.aliases())
					mappings.redirect(alias, l.path());
			});
		locations()
			.filter(l -> l.redirect() != null)
			.forEach(l -> {
				mappings.redirect(l.path(), l.redirect());
				for (String alias : l.aliases())
					mappings.redirect(alias, l.redirect());
			});
		locations()
			.filter(l -> l.gone())
			.forEach(l -> {
				mappings.gone(l.path());
				for (String alias : l.aliases())
					mappings.gone(alias);
			});
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
	public String title() {
		return null;
	}
	public String language() {
		return null;
	}
	public SiteIcon favicon() {
		return null;
	}
	public SiteLocation locationSetup() {
		return null;
	}
	private Supplier<SiteLocation> locationRoot = Suppliers.memoize(() -> {
		SiteLocation root = locationSetup();
		if (root == null)
			return null;
		root.compile(this);
		return root;
	});
	public SiteLocation locationRoot() {
		return locationRoot.get();
	}
	public Stream<SiteLocation> locations() {
		if (locationRoot() == null)
			return Stream.empty();
		return locationRoot().flatten();
	}
	public SitePage viewer() {
		return new SitePage();
	}
	public SiteConfiguration() {
		mappings
			.map("/pushmode/poller", new PollServlet())
			.map("/pushmode/submit", new SubmitServlet())
			.map("/pushmode/script", new PushScriptServlet())
			.map("/sitemap.xml", new SitemapServlet(this::sitemap));
	}
	public SitemapGenerator sitemap() {
		SitemapGenerator sitemap = SitemapGenerator.of(uri().toString());
		locations()
			.filter(l -> l.page() != null)
			.forEach(l -> {
				WebPageBuilder entry = WebPage.builder().name(l.path());
				if (l.priority().isPresent())
					entry.priority(l.priority().getAsDouble());
				sitemap.addPage(entry.build());
			});
		return sitemap;
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
