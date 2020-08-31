// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;
import org.apache.commons.io.*;
import org.eclipse.jetty.servlet.*;
import com.machinezoo.hookless.*;
import com.machinezoo.hookless.prefs.*;
import com.machinezoo.hookless.servlets.*;
import com.machinezoo.hookless.util.*;
import com.machinezoo.noexception.*;
import com.machinezoo.pmsite.utils.*;
import com.machinezoo.pushmode.*;
import com.machinezoo.pushmode.dom.*;
import com.machinezoo.stagean.*;
import cz.jiripinkas.jsitemapgenerator.*;
import cz.jiripinkas.jsitemapgenerator.WebPage.*;
import cz.jiripinkas.jsitemapgenerator.generator.*;

/**
 * Definition of single site (domain or subdomain).
 */
@StubDocs
@DraftApi
public abstract class SiteConfiguration {
	private URI uri;
	public URI uri() {
		return uri;
	}
	public SiteConfiguration uri(URI uri) {
		this.uri = uri;
		OwnerTrace.of(this).tag("uri", uri);
		return this;
	}
	public ReactivePreferences preferences() {
		/*
		 * We will persist preferences via JRE implementation of Preferences, which is horribly inefficient.
		 * It will however work acceptably well for local single-user apps as is common with data science apps.
		 * Applications exposed on the web should configure better implementation either on Preferences or ReactivePreferences level.
		 * Even locally running applications might be better off at least calling SitePreferences.storeIn().
		 */
		return ReactivePreferences.userRoot();
	}
	public ReactivePreferences intercept(ReactivePreferences prefs) {
		/*
		 * Derived classes can put arbitrary filter on top of SiteFragment-defined preferences.
		 * This is especially useful for site-wide timeout/non-blocking wrapper.
		 */
		return prefs;
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
	protected SiteLocation enumerate() {
		return null;
	}
	protected void extras(SiteLocation root) {
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
	private static class SiteMap {
		final SiteLocation tree;
		final Map<String, SiteLocation> paths = new HashMap<>();
		final Map<String, SiteLocation> subtrees = new HashMap<>();
		final Map<String, String> hashes = new HashMap<>();
		SiteMap(SiteLocation tree) {
			this.tree = tree;
			tree.descendantsAndSelf()
				.filter(l -> !l.virtual() && l.path() != null)
				.forEach(l -> {
					Stream.concat(Stream.of(l.path()), l.aliases().stream()).forEach(p -> {
						if (paths.containsKey(p))
							throw new IllegalStateException("Duplicate path mapping: " + p);
						paths.put(p, l);
					});
				});
			tree.descendantsAndSelf()
				.filter(l -> !l.virtual() && l.subtree() != null)
				.forEach(l -> {
					if (subtrees.containsKey(l.subtree()))
						throw new IllegalStateException("Duplicate subtree mapping: " + l.subtree());
					subtrees.put(l.subtree(), l);
				});
			tree.descendantsAndSelf()
				.filter(l -> l.asset() != null && l.path() != null)
				.forEach(Exceptions.log().consumer(Exceptions.sneak().consumer(l -> {
					try (InputStream stream = SiteConfiguration.class.getResourceAsStream(l.asset())) {
						if (stream == null)
							throw new IllegalStateException("Resource not found: " + l.asset());
						byte[] content = IOUtils.toByteArray(stream);
						/*
						 * This calculation must be the same as the one used when serving the resource.
						 */
						byte[] sha256 = Exceptions.sneak().get(() -> MessageDigest.getInstance("SHA-256")).digest(content);
						hashes.put(l.path(), Base64.getUrlEncoder().encodeToString(sha256).replace("_", "").replace("-", "").replace("=", ""));
					}
				})));
		}
	}
	private static void compileTree(SiteLocation location) {
		location.compile();
		for (var child : location.children()) {
			child.parent(location);
			compileTree(child);
		}
	}
	private SiteMap map(SiteLocation tree) {
		tree.site(this);
		compileTree(tree);
		return new SiteMap(tree);
	}
	private SiteMap build() {
		/*
		 * Rebuild the location tree whenever some template changes.
		 */
		SiteReload.watch();
		var tree = enumerate();
		if (tree == null)
			tree = new SiteLocation();
		extras(tree);
		return map(tree);
	}
	private Supplier<SiteMap> locations = OwnerTrace
		.of(new ReactiveWorker<>(this::build)
			.initial(map(new SiteLocation())))
		.parent(this)
		.tag("role", "locations")
		.target();
	public SiteLocation home() {
		return locations.get().tree;
	}
	/*
	 * TODO: Any way to use standard APIs not tied to jetty?
	 */
	protected void registerServlets(ServletContextHandler handler) {
	}
	public SitePage viewer() {
		return new SitePage();
	}
	public void intercept(SiteLocation location) {
		if (location.status() == 410 && location.body() == null && location.main() == null && location.article() == null) {
			location.main(Html.main()
				.clazz("gone-page")
				.add(Html.p()
					.add("This content is no longer available."))
				.add(Html.p()
					.add("See ")
					.add(Html.a()
						.href("/")
						.add("homepage"))
					.add(".")));
		}
	}
	public SiteLocation location(String path) {
		if (!path.startsWith("/"))
			throw new IllegalArgumentException();
		var map = locations.get();
		var exact = map.paths.get(path);
		if (exact != null)
			return exact;
		var subtree = path.endsWith("/") ? path : path.substring(0, path.lastIndexOf('/') + 1);
		while (true) {
			var match = map.subtrees.get(subtree);
			if (match != null)
				return match;
			if (subtree.equals("/"))
				return null;
			subtree = subtree.substring(0, subtree.lastIndexOf('/', subtree.length() - 2) + 1);
		}
	}
	public String asset(String path) {
		if (path.startsWith("http"))
			return path;
		String hash = locations.get().hashes.get(path);
		String buster = hash != null ? "?v=" + hash : SiteReload.buster();
		return path + buster;
	}
	public SitemapGenerator sitemap() {
		SitemapGenerator sitemap = SitemapGenerator.of(uri().toString());
		home().descendantsAndSelf()
			.filter(l -> l.status() == 0 && l.asset() == null && l.servlet() == null)
			.forEach(l -> {
				WebPageBuilder entry = WebPage.builder().name(l.path());
				if (l.priority().isPresent())
					entry.priority(l.priority().getAsDouble());
				sitemap.addPage(entry.build());
			});
		return sitemap;
	}
	@SuppressWarnings("serial")
	private static class SitemapServlet extends ReactiveServlet {
		final Supplier<SitemapGenerator> supplier;
		SitemapServlet(Supplier<SitemapGenerator> supplier) {
			this.supplier = supplier;
		}
		@Override
		public ReactiveServletResponse doGet(ReactiveServletRequest request) {
			var sitemap = supplier.get();
			byte[] data = sitemap.toString().getBytes(StandardCharsets.UTF_8);
			var response = new ReactiveServletResponse();
			response.headers().put("Content-Type", "text/xml; charset=utf-8");
			response.headers().put("Content-Length", Integer.toString(data.length));
			response.headers().put("Cache-Control", "public, max-age=86400");
			response.data(ByteBuffer.wrap(data));
			return response;
		}
	}
}
