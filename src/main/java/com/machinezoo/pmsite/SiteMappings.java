// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite;

import static java.util.stream.Collectors.*;
import java.io.*;
import java.lang.ref.*;
import java.net.*;
import java.nio.*;
import java.security.*;
import java.util.*;
import java.util.function.*;
import java.util.regex.*;
import javax.servlet.http.*;
import org.apache.commons.io.*;
import org.apache.http.*;
import org.apache.http.client.utils.*;
import org.eclipse.jetty.servlet.*;
import com.machinezoo.hookless.*;
import com.machinezoo.hookless.servlets.*;
import com.machinezoo.noexception.*;
import com.machinezoo.pushmode.*;

/*
 * Mappings from URLs to resources (pages, content, redirects, ...).
 */
public class SiteMappings {
	private final SiteConfiguration site;
	/*
	 * It is simpler to insert registered servlets directly into the handler
	 * rather than building a map of servlets and then flushing it to a new handler.
	 * We don't have to fully duplicate handler's data structures this way.
	 */
	private final ServletContextHandler handler;
	public ServletContextHandler handler() {
		/*
		 * Only add the routing servlet after the site is fully initialized in order to
		 * avoid race rules between eager refresh of routing cache and site initialization.
		 */
		add("/", new DynamicServlet(() -> new RoutingServlet(site, site.locations().collect(toList()))));
		flushFallbacks();
		return handler;
	}
	public SiteMappings(SiteConfiguration site) {
		this.site = site;
		handler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
	}
	/*
	 * Since servlet mapping patterns are confusing and surprising, we replace them with plain paths.
	 * The only downside is that we have to offer more methods to cover the various mapping types.
	 * 
	 * Tracking already mapped patterns is useful to detect duplicate mappings early
	 * and to tell which fallback mappings will be actually used.
	 */
	private Set<String> patterns = new HashSet<>();
	private SiteMappings add(String pattern, HttpServlet servlet) {
		if (patterns.contains(pattern))
			throw new IllegalArgumentException("Duplicate mapping pattern: " + pattern);
		ServletHolder holder = new ServletHolder(servlet);
		/*
		 * Essential to make reactive servlets work.
		 */
		holder.setAsyncSupported(true);
		handler.addServlet(holder, pattern);
		patterns.add(pattern);
		return this;
	}
	/*
	 * Fallbacks are mostly used internally for the root folder
	 * and to workaround a quirk in how servlet patterns behave for subtrees.
	 * Fallbacks are exposed as public API in case they are found useful in site configuration.
	 * We allow mapping to any servlet, but internally we only use the 404 servlet.
	 */
	private Map<String, HttpServlet> fallbacks = new HashMap<>();
	public void fallback(String pattern, HttpServlet servlet) {
		fallbacks.put(pattern, servlet);
	}
	private void flushFallbacks() {
		for (Map.Entry<String, HttpServlet> mapping : fallbacks.entrySet())
			if (!patterns.contains(mapping.getKey()))
				add(mapping.getKey(), mapping.getValue());
		fallbacks.clear();
	}
	@SuppressWarnings("serial") private static class NotFoundServlet extends ReactiveServlet {
		@Override public ReactiveServletResponse doGet(ReactiveServletRequest request) {
			ReactiveServletResponse response = new ReactiveServletResponse();
			response.status(HttpServletResponse.SC_NOT_FOUND);
			response.headers().put("Cache-Control", "no-cache, no-store");
			return response;
		}
	}
	/*
	 * Allow mapping of plain non-reactive servlets, which is currently most useful for websockets.
	 * The same methods are used for reactive servlets, which inherit from plain servlets.
	 */
	public SiteMappings map(String path, HttpServlet servlet) {
		if (path.contains("*"))
			throw new IllegalArgumentException("Path must not contain wildcards: " + path);
		if (!path.startsWith("/"))
			throw new IllegalArgumentException("Path must start with '/': " + path);
		return add(path.equals("/") ? "" : path, servlet);
	}
	public SiteMappings subtree(String path, HttpServlet servlet) {
		if (path.contains("*"))
			throw new IllegalArgumentException("Path must not contain wildcards: " + path);
		if (!path.startsWith("/"))
			throw new IllegalArgumentException("Path must start with '/': " + path);
		if (!path.endsWith("/"))
			throw new IllegalArgumentException("Path must end with '/': " + path);
		if (path.equals("/"))
			add("/", servlet);
		else {
			add(path + "*", servlet);
			/*
			 * Servlet mapping path /dir/* will surprisingly match not only /dir/ but also /dir.
			 * We will prevent this behaviour by explicitly mapping /dir to a 404 servlet.
			 * This is only a fallback in case the application wants to map the path to something else.
			 */
			fallback(path.substring(0, path.length() - 1), new NotFoundServlet());
		}
		return this;
	}
	/*
	 * We currently don't bother supporting multiple values in If-None-Match.
	 * The only downside is reduced performance in rare cases when If-None-Match actually contains multiple values.
	 */
	private static final Pattern etagRe = Pattern.compile("\\s*\"([^\"]+)\"\\s*");
	@SuppressWarnings("serial") private static class ResourceServlet extends ReactiveServlet {
		final String filename;
		ResourceServlet(String filename) {
			this.filename = filename;
		}
		/*
		 * Make sure the resource is loaded into memory at most once.
		 */
		WeakReference<byte[]> cache;
		synchronized byte[] load() {
			byte[] content = null;
			if (cache != null)
				content = cache.get();
			if (content == null) {
				content = Exceptions.sneak().get(() -> {
					try (InputStream stream = ResourceServlet.class.getResourceAsStream(filename)) {
						if (stream == null)
							throw new IllegalStateException("Resource not found: " + filename);
						return IOUtils.toByteArray(stream);
					}
				});
				cache = new WeakReference<>(content);
			}
			return content;
		}
		/*
		 * Cache content hash to speed up request processing.
		 * In case of 304 responses, we just need the hash and not the content.
		 */
		String hash;
		synchronized String etag() {
			if (hash == null) {
				byte[] sha256 = Exceptions.sneak().get(() -> MessageDigest.getInstance("SHA-256")).digest(load());
				hash = Base64.getUrlEncoder().encodeToString(sha256).replace("_", "").replace("-", "").replace("=", "");
			}
			return hash;
		}
		@Override public ReactiveServletResponse doGet(ReactiveServletRequest request) {
			String etag = etag();
			ReactiveServletResponse response = new ReactiveServletResponse();
			/*
			 * Use ETags to avoid redownloads of resources when server restarts.
			 */
			response.headers().put("ETag", "\"" + etag + "\"");
			String inm = request.headers().get("If-None-Match");
			if (inm != null) {
				Matcher matcher = etagRe.matcher(inm);
				if (matcher.matches() && etag.equals(matcher.group(1))) {
					response.status(HttpServletResponse.SC_NOT_MODIFIED);
					return response;
				}
			}
			byte[] content = load();
			/*
			 * Resources need long-lived caching and at the same time immediate refresh upon change.
			 * We force the refresh by embedding URLs with cache busters in all pages that use the resource.
			 * It may however happen that we receive request for a hash that we don't have.
			 * This can easily happen when page is served before server upgrade and resource is served afterwards.
			 * We solve this problem by checking the hash every time and disable caching if we serve the wrong version.
			 * Page streaming will soon afterwards push correct URL to the client
			 * and we then get an opportunity to serve the right content with caching enabled.
			 * 
			 * This is unfortunately broken in the reverse direction when the correct new hash is served,
			 * but old server responds with old version of the content. The problem will correct itself
			 * after page reload, but there's no way to fix it while the page is still displayed.
			 * The only solution is to use data URIs and upload the more frequently served resources
			 * to versioned CDN storage, subsequently replacing data URIs with CDN URLs.
			 */
			String version = Exceptions.sneak().get(() -> new URIBuilder(request.url())).getQueryParams().stream()
				.filter(p -> p.getName().equals("v"))
				.findFirst()
				.map(NameValuePair::getValue)
				.orElse(null);
			/*
			 * This also covers the case when the version query parameter is not present.
			 */
			if (!etag.equals(version))
				response.headers().put("Cache-Control", "no-cache, no-store");
			else
				response.headers().put("Cache-Control", "public, max-age=31536000"); // one year
			response.headers().put("Content-Type", MimeTypes.byPath(filename).orElse("application/octet-stream"));
			response.data(ByteBuffer.wrap(content));
			return response;
		}
	}
	@SuppressWarnings("serial") private static class RedirectServlet extends ReactiveServlet {
		private final Function<String, String> rule;
		RedirectServlet(Function<String, String> rule) {
			this.rule = rule;
		}
		@Override public ReactiveServletResponse doGet(ReactiveServletRequest request) {
			ReactiveServletResponse response = new ReactiveServletResponse();
			response.status(HttpServletResponse.SC_MOVED_PERMANENTLY);
			/*
			 * Cache 301s only for one day to make sure we can fix bad 301s.
			 */
			response.headers().put("Cache-Control", "public, max-age=86400");
			response.headers().put("Location", rule.apply(request.url()));
			return response;
		}
	}
	@SuppressWarnings("serial") private static class GoneServlet extends PageServlet {
		public GoneServlet(Supplier<PushPage> supplier) {
			super(supplier);
		}
		@Override protected ReactiveServletResponse response() {
			ReactiveServletResponse response = super.response();
			response.status(HttpServletResponse.SC_GONE);
			return response;
		}
	}
	@SuppressWarnings("serial") private static class RoutingServlet extends ReactiveServlet {
		final Map<String, ReactiveServlet> paths = new HashMap<>();
		void add(String path, ReactiveServlet servlet) {
			if (paths.containsKey(path))
				throw new IllegalStateException("Duplicate path: " + path);
			paths.put(path, servlet);
		}
		final Map<String, ReactiveServlet> subtrees = new HashMap<>();
		void redirectAliases(ReactiveServlet servlet, SiteLocation location) {
			add(location.path(), servlet);
			for (String alias : location.aliases())
				add(alias, new RedirectServlet(u -> location.path()));
		}
		void duplicateOnAliases(ReactiveServlet servlet, SiteLocation location) {
			add(location.path(), servlet);
			for (String alias : location.aliases())
				add(alias, servlet);
		}
		void subtree(String subtree, ReactiveServlet servlet) {
			if (subtrees.containsKey(subtree))
				throw new IllegalStateException("Duplicate subtree: " + subtree);
			subtrees.put(subtree, servlet);
		}
		RoutingServlet(SiteConfiguration configuration, List<SiteLocation> locations) {
			for (SiteLocation location : locations) {
				if (location.path() != null) {
					if (location.page() != null)
						redirectAliases(new PageServlet(() -> location.page().get().location(location)), location);
					else if (location.redirect() != null)
						duplicateOnAliases(new RedirectServlet(u -> location.redirect()), location);
					else if (location.rewrite() != null)
						duplicateOnAliases(new RedirectServlet(location.rewrite()), location);
					else if (location.gone())
						duplicateOnAliases(new GoneServlet(configuration::gone), location);
					else if (location.servlet() != null)
						redirectAliases(location.servlet(), location);
					else if (location.resource() != null)
						redirectAliases(new ResourceServlet(location.resource()), location);
					else
						throw new IllegalStateException();
				} else if (location.subtree() != null) {
					if (location.page() != null)
						subtree(location.subtree(), new PageServlet(() -> location.page().get().location(location)));
					else if (location.redirect() != null)
						subtree(location.subtree(), new RedirectServlet(u -> location.redirect()));
					else if (location.rewrite() != null)
						subtree(location.subtree(), new RedirectServlet(location.rewrite()));
					else if (location.gone())
						subtree(location.subtree(), new GoneServlet(configuration::gone));
					else if (location.servlet() != null)
						subtree(location.subtree(), location.servlet());
					else
						throw new IllegalStateException();
				} else if (!location.virtual())
					throw new IllegalStateException();
			}
		}
		static final ReactiveServlet fallback = new NotFoundServlet();
		ReactiveServlet route(String path) {
			if (paths.containsKey(path))
				return paths.get(path);
			if (path.startsWith("/")) {
				String subtree = path.endsWith("/") ? path : path.substring(0, path.lastIndexOf('/') + 1);
				while (true) {
					if (subtrees.containsKey(subtree))
						return subtrees.get(subtree);
					if (subtree.equals("/"))
						break;
					subtree = subtree.substring(0, subtree.lastIndexOf('/', subtree.length() - 2) + 1);
				}
			}
			return fallback;
		}
		@Override public ReactiveServletResponse service(ReactiveServletRequest request) {
			return route(Exceptions.sneak().get(() -> new URI(request.url()).getPath())).service(request);
		}
	}
	@SuppressWarnings("serial") private static class DynamicServlet extends ReactiveServlet {
		static final ReactiveServlet fallback = new NotFoundServlet();
		final Supplier<ReactiveServlet> cache;
		DynamicServlet(Supplier<ReactiveServlet> supplier) {
			/*
			 * Eager refresh is necessary for partially non-reactive outputs like request routing.
			 * Default is set to reactively block, so nothing is sent to the client until we have the actual location tree.
			 */
			cache = new ReactiveBatchCache<>(Exceptions.log().supplier(supplier).orElse(fallback)).draft(fallback).weak(true).start()::get;
		}
		@Override public ReactiveServletResponse service(ReactiveServletRequest request) {
			return cache.get().service(request);
		}
	}
}
