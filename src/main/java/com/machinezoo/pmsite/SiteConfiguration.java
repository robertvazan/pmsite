// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite;

import static java.util.stream.Collectors.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;
import org.apache.commons.io.*;
import com.machinezoo.hookless.*;
import com.machinezoo.hookless.servlets.*;
import com.machinezoo.noexception.*;
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
	protected final SiteMappings mappings = new SiteMappings(this);
	public String title() {
		return null;
	}
	public String language() {
		return null;
	}
	public SiteIcon favicon() {
		return null;
	}
	protected SiteLocation locationSetup() {
		return null;
	}
	protected void locationExtras(SiteLocation root) {
		root
			.add(new SiteLocation()
				.path("/pushmode/poller")
				.servlet(new PollServlet()))
			.add(new SiteLocation()
				.path("/pushmode/submit")
				.servlet(new SubmitServlet()))
			.add(new SiteLocation()
				.path("/pushmode/script")
				.servlet(new PushScriptServlet()))
			.add(new SiteLocation()
				.path("/sitemap.xml")
				.servlet(new SitemapServlet(this::sitemap)));
	}
	private SiteLocation locationDefault() {
		SiteLocation fallback = new SiteLocation()
			.page(this::viewer);
		fallback.compile(this);
		return fallback;
	}
	private SiteLocation locationBuild() {
		SiteLocation root = locationSetup();
		if (root == null)
			root = locationDefault();
		locationExtras(root);
		root.compile(this);
		return root;
	}
	/*
	 * Eager refresh is done by routing servlet. We only need normal async cache here.
	 * Adding eager refresh here is not only unnecessary, but it also risks race rules,
	 * because site initialization is still running when location tree cache is created.
	 */
	private Supplier<SiteLocation> locationRoot = new ReactiveAsyncCache<>(this::locationBuild).draft(locationDefault())::get;
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
	private Map<String, String> hashes() {
		return Exceptions.log().get(() -> locations()
			.filter(l -> l.resource() != null)
			.collect(toMap(l -> l.path(), Exceptions.sneak().function(l -> {
				try (InputStream stream = SiteConfiguration.class.getResourceAsStream(l.resource())) {
					if (stream == null)
						throw new IllegalStateException("Resource not found: " + l.resource());
					byte[] content = IOUtils.toByteArray(stream);
					/*
					 * This calculation must be the same as the one used when serving the resource.
					 */
					byte[] sha256 = Exceptions.sneak().get(() -> MessageDigest.getInstance("SHA-256")).digest(content);
					return Base64.getUrlEncoder().encodeToString(sha256).replace("_", "").replace("-", "").replace("=", "");
				}
			})))).orElse(Collections.emptyMap());
	}
	private ReactiveAsyncCache<Map<String, String>> hashes = new ReactiveAsyncCache<>(this::hashes).draft(Collections.emptyMap());
	public String asset(String path) {
		if (path.startsWith("http"))
			return path;
		String hash = hashes.get().get(path);
		String buster = hash != null ? "?v=" + hash : SiteReload.buster();
		return path + buster;
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
