// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite;

import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.function.*;
import java.util.stream.*;
import com.machinezoo.hookless.*;
import com.machinezoo.hookless.servlets.*;
import com.machinezoo.pushmode.*;
import cz.jiripinkas.jsitemapgenerator.*;
import cz.jiripinkas.jsitemapgenerator.WebPage.WebPageBuilder;
import cz.jiripinkas.jsitemapgenerator.generator.*;

public abstract class SiteConfiguration {
	private URI uri;
	public URI uri() {
		return uri;
	}
	public SiteConfiguration uri(URI uri) {
		this.uri = uri;
		return this;
	}
	protected final SiteMappings mappings = new SiteMappings(this).contentRoot(getClass());
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
	private SiteLocation locationBuild() {
		SiteLocation root = locationSetup();
		if (root == null)
			return null;
		root.compile(this);
		return root;
	}
	private SiteLocation locationDefault() {
		SiteLocation fallback = new SiteLocation()
			.page(SitePage::new);
		fallback.compile(this);
		return fallback;
	}
	/*
	 * Eager refresh is necessary for partially non-reactive outputs like request routing.
	 * Default is set to reactively block, so nothing is sent to the client until we have the actual location tree.
	 */
	private Supplier<SiteLocation> locationRoot = new ReactiveBatchCache<>(this::locationBuild).draft(locationDefault()).weak(true).start()::get;
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
	public SitePage gone() {
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
