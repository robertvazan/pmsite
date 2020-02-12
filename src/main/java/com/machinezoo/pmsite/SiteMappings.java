// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite;

import static java.util.stream.Collectors.*;
import java.io.*;
import java.lang.ref.*;
import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.function.*;
import javax.servlet.http.*;
import org.apache.commons.io.*;
import org.eclipse.jetty.servlet.*;
import com.machinezoo.hookless.*;
import com.machinezoo.hookless.servlets.*;
import com.machinezoo.noexception.*;
import com.machinezoo.pushmode.*;

/*
 * Mappings from URLs to resources (pages, content, redirects, ...).
 */
public class SiteMappings {
	/*
	 * It is simpler to insert registered servlets directly into the handler
	 * rather than building a map of servlets and then flushing it to a new handler.
	 * We don't have to fully duplicate handler's data structures this way.
	 */
	private final ServletContextHandler handler;
	public ServletContextHandler handler() {
		/*
		 * Fallbacks are the only part that needs to be flushed before the handler is exposed.
		 */
		flushFallbacks();
		return handler;
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
	public SiteMappings(SiteConfiguration site) {
		handler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
		/*
		 * Dynamic routing based on the current location tree is the default.
		 * It can be changed, but location-based routing then stops working.
		 */
		fallback("/", new DynamicServlet(() -> new RoutingServlet(site, site.locations().collect(toList()))));
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
	 * Ordinary site pages. Use the above servlet mappers to use something else than PageServlet.
	 */
	public SiteMappings map(String path, Supplier<? extends PushPage> constructor) {
		return map(path, new PageServlet(constructor));
	}
	public SiteMappings subtree(String path, Supplier<? extends PushPage> constructor) {
		return subtree(path, new PageServlet(constructor));
	}
	/*
	 * Static content that doesn't have version hash.
	 * It is used for standard locations like favicon.ico that aren't referenced from pages and thus don't need hashes.
	 */
	public SiteMappings content(String path, Class<?> clazz, String name) {
		return map(path, new StaticContentServlet(clazz, name));
	}
	public SiteMappings content(String path, Class<?> clazz) {
		return content(path, clazz, path.substring(path.lastIndexOf('/') + 1));
	}
	private Class<?> contentRoot;
	public SiteMappings contentRoot(Class<?> contentRoot) {
		this.contentRoot = contentRoot;
		return this;
	}
	public SiteMappings content(String path) {
		if (contentRoot == null)
			throw new IllegalStateException();
		return content(path, contentRoot);
	}
	@SuppressWarnings("serial") private static class StaticContentServlet extends ReactiveServlet {
		final Class<?> clazz;
		final String filename;
		StaticContentServlet(Class<?> clazz, String filename) {
			this.clazz = clazz;
			this.filename = filename;
		}
		WeakReference<byte[]> cache;
		@Override public ReactiveServletResponse doGet(ReactiveServletRequest request) {
			byte[] content = null;
			synchronized (this) {
				if (cache != null)
					content = cache.get();
				if (content == null) {
					content = Exceptions.sneak().get(() -> {
						try (InputStream stream = clazz.getResourceAsStream(filename)) {
							if (stream == null)
								throw new IllegalStateException("Resource not found: " + clazz.getName() + ": " + filename);
							return IOUtils.toByteArray(stream);
						}
					});
					cache = new WeakReference<>(content);
				}
			}
			ReactiveServletResponse response = new ReactiveServletResponse();
			response.headers().put("Cache-Control", "public, max-age=86400"); // one day
			response.headers().put("Content-Type", MimeTypes.byPath(filename).orElse("application/octet-stream"));
			response.data(ByteBuffer.wrap(content));
			return response;
		}
	}
	/*
	 * Permanent redirects (301s).
	 */
	public SiteMappings redirect(String path, String target) {
		return map(path, new RedirectServlet(target));
	}
	public SiteMappings redirectTreeToPage(String path, String target) {
		return subtree(path, new RedirectServlet(target));
	}
	@SuppressWarnings("serial") private static class RedirectServlet extends ReactiveServlet {
		private final String location;
		RedirectServlet(String location) {
			this.location = location;
		}
		@Override public ReactiveServletResponse doGet(ReactiveServletRequest request) {
			ReactiveServletResponse response = new ReactiveServletResponse();
			response.status(HttpServletResponse.SC_MOVED_PERMANENTLY);
			/*
			 * Cache 301s only for one day to make sure we can fix bad 301s.
			 */
			response.headers().put("Cache-Control", "public, max-age=86400");
			response.headers().put("Location", location);
			return response;
		}
	}
	public SiteMappings redirectTreeToPrefix(String path, String prefix) {
		return subtree(path, new TreeRedirectServlet(path, prefix));
	}
	@SuppressWarnings("serial") private static class TreeRedirectServlet extends ReactiveServlet {
		private final String path;
		private final String prefix;
		TreeRedirectServlet(String path, String prefix) {
			this.path = path;
			this.prefix = prefix;
		}
		@Override public ReactiveServletResponse doGet(ReactiveServletRequest request) {
			ReactiveServletResponse response = new ReactiveServletResponse();
			response.status(HttpServletResponse.SC_MOVED_PERMANENTLY);
			/*
			 * Cache 301s only for one day to make sure we can fix bad 301s.
			 */
			response.headers().put("Cache-Control", "public, max-age=86400");
			/*
			 * Check that we are actually passed URL that we are supposed to handle.
			 * Web request processing is complext and we could ea
			 */
			String original = Exceptions.sneak().get(() -> new URI(request.url())).getPath();
			if (!original.startsWith(path))
				throw new IllegalArgumentException();
			/*
			 * All paths processed internally are decoded, so encode the resulting path before sending it to the client.
			 */
			URI location = Exceptions.sneak().get(() -> new URI(null, null, prefix + original.substring(path.length()), null));
			response.headers().put("Location", location.toString());
			return response;
		}
	}
	/*
	 * Gone resources (410s).
	 * These require configuration of a site-specific 410 page since real people can see them.
	 */
	private GoneServlet gone;
	public SiteMappings gone(Supplier<PushPage> supplier) {
		gone = new GoneServlet(supplier);
		return this;
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
	public SiteMappings gone(String path) {
		if (gone == null)
			throw new IllegalStateException();
		return map(path, gone);
	}
	public SiteMappings goneTree(String path) {
		if (gone == null)
			throw new IllegalStateException();
		return subtree(path, gone);
	}
	@SuppressWarnings("serial") private static class RoutingServlet extends ReactiveServlet {
		final Map<String, ReactiveServlet> simple = new HashMap<>();
		final ReactiveServlet fallback = new NotFoundServlet();
		void add(String path, ReactiveServlet servlet) {
			if (simple.containsKey(path))
				throw new IllegalStateException("Duplicate path: " + path);
			simple.put(path, servlet);
		}
		RoutingServlet(SiteConfiguration configuration, List<SiteLocation> locations) {
			for (SiteLocation location : locations) {
				if (location.page() != null) {
					add(location.path(), new PageServlet(() -> location.page().get().location(location)));
					for (String alias : location.aliases())
						add(alias, new RedirectServlet(location.path()));
				} else if (location.redirect() != null) {
					add(location.path(), new RedirectServlet(location.redirect()));
					for (String alias : location.aliases())
						add(alias, new RedirectServlet(location.redirect()));
				} else if (location.gone()) {
					add(location.path(), new GoneServlet(configuration::gone));
					for (String alias : location.aliases())
						add(alias, new GoneServlet(configuration::gone));
				}
			}
		}
		@Override public ReactiveServletResponse service(ReactiveServletRequest request) {
			String path = Exceptions.sneak().get(() -> new URI(request.url()).getPath());
			return simple.getOrDefault(path, fallback).service(request);
		}
	}
	@SuppressWarnings("serial") private static class DynamicServlet extends ReactiveServlet {
		final Supplier<ReactiveServlet> cache;
		DynamicServlet(Supplier<ReactiveServlet> supplier) {
			/*
			 * Eager refresh is necessary for partially non-reactive outputs like request routing.
			 * Default is set to reactively block, so nothing is sent to the client until we have the actual location tree.
			 */
			cache = new ReactiveBatchCache<>(supplier).draft(new NotFoundServlet()).weak(true).start()::get;
		}
		@Override public ReactiveServletResponse service(ReactiveServletRequest request) {
			return cache.get().service(request);
		}
	}
}
