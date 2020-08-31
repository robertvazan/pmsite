// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite;

import static java.util.stream.Collectors.*;
import java.io.*;
import java.lang.ref.*;
import java.net.*;
import java.nio.*;
import java.security.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.*;
import javax.servlet.http.*;
import org.apache.commons.io.*;
import org.apache.http.*;
import org.apache.http.client.utils.*;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.handler.*;
import org.eclipse.jetty.servlet.*;
import org.eclipse.jetty.util.*;
import org.eclipse.jetty.util.thread.*;
import com.google.common.base.*;
import com.machinezoo.hookless.*;
import com.machinezoo.hookless.servlets.*;
import com.machinezoo.noexception.*;
import com.machinezoo.pmsite.utils.*;
import com.machinezoo.pushmode.*;
import com.machinezoo.stagean.*;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;

/**
 * Exposes all sites' locations via embedded jetty server.
 * This code assumes that HTTPS is provided by reverse proxy (Apache, nginx, ...).
 */
@StubDocs
@DraftApi
public class SiteServer {
	/*
	 * We will define a number of reactive servlets, each handling particular type of location.
	 * We will make them public, so that app code can use customized version of these servlets.
	 */
	public static class NotFound extends ReactiveServlet {
		private static final long serialVersionUID = 1L;
		@Override
		public ReactiveServletResponse doGet(ReactiveServletRequest request) {
			var response = new ReactiveServletResponse();
			response.status(HttpServletResponse.SC_NOT_FOUND);
			response.headers().put("Cache-Control", "no-cache, no-store");
			return response;
		}
	}
	private static final NotFound miss = new NotFound();
	public static class Page extends ReactiveServlet {
		private static final long serialVersionUID = 1L;
		private final Supplier<? extends PushPage> supplier;
		public Page(Supplier<? extends PushPage> supplier) {
			this.supplier = supplier;
		}
		private static final Timer timer = Metrics.timer("http.page");
		@Override
		public ReactiveServletResponse doGet(ReactiveServletRequest request) {
			var sample = CurrentReactiveScope.pin("timer", () -> Timer.start(Clock.SYSTEM));
			SiteLaunch.profile("Page servlet started processing the first request.");
			try {
				var page = CurrentReactiveScope.pin("page", () -> {
					PushPage created = supplier.get();
					PagePool.instance().add(created);
					created.serve(request);
					return created;
				});
				var response = CurrentReactiveScope.pin("response", () -> {
					ReactiveServletResponse proposed = response();
					page.serve(proposed);
					return proposed;
				});
				/*
				 * Do not continue with poster frame until the response is ready.
				 */
				if (CurrentReactiveScope.blocked())
					return response;
				page.start();
				var frame = page.frame(0);
				if (CurrentReactiveScope.blocked())
					return response;
				response.data(ByteBuffer.wrap(frame.serialize()));
				sample.stop(timer);
				return response;
			} catch (Throwable ex) {
				/*
				 * Log exceptions even when blocking if this runs in the test environment.
				 */
				if (SiteRunMode.get() != SiteRunMode.PRODUCTION || CurrentReactiveScope.blocked())
					Exceptions.log().handle(ex);
				throw ex;
			}
		}
		protected ReactiveServletResponse response() {
			var response = new ReactiveServletResponse();
			response.headers().put("Content-Type", "text/html; charset=utf-8");
			response.headers().put("Cache-Control", "no-cache, no-store");
			return response;
		}
	}
	static Page managedPage(SiteConfiguration site, Supplier<? extends SitePage> supplier) {
		return new Page(() -> {
			var page = supplier.get();
			page.site(site);
			return page;
		});
	}
	static Page reflectedPage(SiteConfiguration site, Class<? extends SitePage> clazz) {
		var cache = Suppliers.memoize(() -> {
			var constructor = Exceptions.sneak().get(() -> clazz.getDeclaredConstructor());
			if (!constructor.canAccess(null))
				constructor.setAccessible(true);
			return constructor;
		});
		return managedPage(site, Exceptions.sneak().supplier(() -> cache.get().newInstance()));
	}
	/*
	 * We currently don't bother supporting multiple values in If-None-Match.
	 * The only downside is reduced performance in rare cases when If-None-Match actually contains multiple values.
	 */
	private static final Pattern etagRe = Pattern.compile("\\s*\"([^\"]+)\"\\s*");
	public static class Resource extends ReactiveServlet {
		private static final long serialVersionUID = 1L;
		private final String filename;
		public Resource(String filename) {
			this.filename = filename;
		}
		/*
		 * Make sure the resource is loaded into memory at most once.
		 */
		private WeakReference<byte[]> cache;
		private synchronized byte[] load() {
			byte[] content = null;
			if (cache != null)
				content = cache.get();
			if (content == null) {
				content = Exceptions.sneak().get(() -> {
					try (InputStream stream = Resource.class.getResourceAsStream(filename)) {
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
		private String hash;
		private synchronized String etag() {
			if (hash == null) {
				byte[] sha256 = Exceptions.sneak().get(() -> MessageDigest.getInstance("SHA-256")).digest(load());
				hash = Base64.getUrlEncoder().encodeToString(sha256).replace("_", "").replace("-", "").replace("=", "");
			}
			return hash;
		}
		@Override
		public ReactiveServletResponse doGet(ReactiveServletRequest request) {
			String etag = etag();
			var response = new ReactiveServletResponse();
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
			var content = load();
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
			response.headers().put("Content-Type", SiteMime.byPath(filename).orElse("application/octet-stream"));
			response.data(ByteBuffer.wrap(content));
			return response;
		}
	}
	public static class Redirect extends ReactiveServlet {
		private static final long serialVersionUID = 1L;
		private final Function<ReactiveServletRequest, URI> rule;
		public Redirect(Function<ReactiveServletRequest, URI> rule) {
			this.rule = rule;
		}
		@Override
		public ReactiveServletResponse doGet(ReactiveServletRequest request) {
			var response = new ReactiveServletResponse();
			response.status(HttpServletResponse.SC_MOVED_PERMANENTLY);
			/*
			 * Cache 301s only for one day to make sure we can fix bad 301s.
			 */
			response.headers().put("Cache-Control", "public, max-age=86400");
			response.headers().put("Location", rule.apply(request).toString());
			return response;
		}
	}
	/*
	 * We don't want to rely on servlet container's servlet mapping API,
	 * because it is static and we want apps to be able to generate (and update) mappings reactively.
	 * Servlet mapping API is also a bit convoluted and limited in the kinds of mappings supported.
	 * 
	 * We will instead define a routing servlet that dynamically picks one of its child servlets based on the path.
	 * This works only with reactive servlets, so direct HttpServlet implementations will need a workaround.
	 */
	public static class Router extends ReactiveServlet {
		private static final long serialVersionUID = 1L;
		private final Map<String, ReactiveServlet> paths = new HashMap<>();
		public void path(String path, ReactiveServlet servlet) {
			if (paths.containsKey(path))
				throw new IllegalStateException("Duplicate path: " + path);
			paths.put(path, servlet);
		}
		private final Map<String, ReactiveServlet> subtrees = new HashMap<>();
		public void subtree(String subtree, ReactiveServlet servlet) {
			if (subtrees.containsKey(subtree))
				throw new IllegalStateException("Duplicate subtree: " + subtree);
			subtrees.put(subtree, servlet);
		}
		public ReactiveServlet route(String path) {
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
			return miss;
		}
		@Override
		public ReactiveServletResponse service(ReactiveServletRequest request) {
			return route(Exceptions.sneak().get(() -> new URI(request.url()).getPath())).service(request);
		}
	}
	/*
	 * This servlet takes reactivity to the next level. Not only is the servlet output reactive,
	 * but also the servlet instance itself is reactive, i.e. the servlet is chosen and configured based on current state of reactive data sources.
	 * The downside is that the inner servlet cannot rely on instance variables to be long-lived, so caching must be done differently.
	 */
	public static class Indirect extends ReactiveServlet {
		private static final long serialVersionUID = 1L;
		/*
		 * Servlet construction might be expensive, so cache the instance reactively.
		 */
		private final Supplier<ReactiveServlet> cache;
		public Indirect(Supplier<ReactiveServlet> supplier) {
			/*
			 * Reactive worker is smart enough to never give us stale value without marking it as blocking.
			 * This is essential for partially non-reactive outputs like request routing.
			 * Default is set to reactively block, so nothing is sent to the client until we have the actual servlet.
			 */
			cache = new ReactiveWorker<ReactiveServlet>()
				.supplier(() -> {
					try {
						return supplier.get();
					} catch (Throwable ex) {
						/*
						 * We want to hear about the exception early, not only after someone requests the page.
						 */
						if (!CurrentReactiveScope.blocked())
							Exceptions.log().handle(ex);
						throw ex;
					}
				})
				.initial(miss);
		}
		@Override
		public ReactiveServletResponse service(ReactiveServletRequest request) {
			return cache.get().service(request);
		}
	}
	/*
	 * We can now put it all together to define dynamic location-based request routing.
	 */
	public static class SiteRouter extends Router {
		private static final long serialVersionUID = 1L;
		private void concentrate(ReactiveServlet servlet, SiteLocation location) {
			path(location.path(), servlet);
			for (String alias : location.aliases())
				path(alias, new Redirect(Exceptions.sneak().function(rq -> new URI(null, null, location.path(), null))));
		}
		private void duplicate(ReactiveServlet servlet, SiteLocation location) {
			path(location.path(), servlet);
			for (String alias : location.aliases())
				path(alias, servlet);
		}
		public SiteRouter(SiteConfiguration site) {
			for (var location : site.home().descendantsAndSelf().collect(toList())) {
				if (!location.virtual()) {
					ReactiveServlet servlet;
					if (location.servlet() != null)
						servlet = location.servlet();
					else if (location.redirect() != null)
						servlet = new Redirect(location.redirect());
					else if (location.asset() != null)
						servlet = new Resource(location.asset());
					else if (location.clazz() != null)
						servlet = reflectedPage(site, location.clazz());
					else if (location.constructor() != null)
						servlet = managedPage(site, location.constructor());
					else
						servlet = managedPage(site, location.viewer());
					if (location.path() != null) {
						if (location.redirect() != null || location.status() == 410 || location.status() == 404)
							duplicate(servlet, location);
						else
							concentrate(servlet, location);
					} else if (location.subtree() != null)
						subtree(location.subtree(), servlet);
					else
						throw new IllegalStateException();
				}
			}
		}
	}
	public static class Host extends ReactiveServlet {
		private static final long serialVersionUID = 1L;
		private final ReactiveServlet inner;
		public Host(SiteConfiguration site) {
			inner = new Indirect(() -> new SiteRouter(site));
		}
		@Override
		public ReactiveServletResponse service(ReactiveServletRequest request) {
			return inner.service(request);
		}
	}
	/*
	 * The rest is just a thin wrapper around jetty.
	 */
	private static Handler handler(SiteConfiguration site) {
		var handler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
		var holder = new ServletHolder(new Host(site));
		/*
		 * Essential to make reactive servlets work.
		 */
		holder.setAsyncSupported(true);
		handler.addServlet(holder, "/");
		site.registerServlets(handler);
		return handler;
	}
	private final Map<String, Supplier<Handler>> lazy = new HashMap<>();
	public SiteServer site(URI uri, Supplier<SiteConfiguration> supplier) {
		lazy.put(uri.getHost(), () -> {
			var site = supplier.get().uri(uri);
			/*
			 * When loaded, eagerly construct the location tree in order to trigger any exceptions caused by incorrect configuration.
			 */
			ReactiveFuture.runReactive(() -> site.home(), SiteThread.bulk());
			return handler(site);
		});
		/*
		 * Force site loading in the background even if there are no requests for it.
		 * This will run all the initialization code and thus throw exceptions in case of configuration errors.
		 */
		SiteLaunch.delay(() -> handler(uri.getHost()));
		return this;
	}
	private final Map<String, Handler> handlers = new HashMap<>();
	private Handler handler(String hostname) {
		Handler handler;
		/*
		 * This is a bit convoluted, but the idea is to run site initialization outside of the synchronized block,
		 * so that multiple sites can be initialized in parallel and so that requests for already initialized sites aren't blocked.
		 */
		synchronized (this) {
			handler = handlers.get(hostname);
		}
		if (handler == null) {
			var supplier = lazy.get(hostname);
			if (supplier != null) {
				synchronized (supplier) {
					synchronized (this) {
						handler = handlers.get(hostname);
					}
					if (handler == null) {
						handler = supplier.get();
						handler.setServer(server);
						Exceptions.sneak().run(handler::start);
						synchronized (this) {
							handlers.put(hostname, handler);
						}
						SiteLaunch.profile("Site {} is configured.", hostname);
					}
				}
			}
		}
		return handler;
	}
	private static Timer timer;
	private class VHostSwitch extends AbstractHandler {
		@Override
		public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) {
			/*
			 * Lazily initialize the timer to avoid loading micrometer before we start listening.
			 * Use plain synchronization instead of Suppliers.memoize() to avoid loading reflection-related classes.
			 */
			if (timer == null)
				timer = Metrics.timer("http.request");
			var sample = Timer.start(Clock.SYSTEM);
			String hostname = baseRequest.getServerName();
			SiteLaunch.profile("Received first request for site {}.", hostname);
			Handler handler = handler(hostname);
			if (handler != null) {
				Exceptions.sneak().run(() -> handler.handle(target, baseRequest, request, response));
				sample.stop(timer);
			}
		}
	}
	private final Server server;
	public SiteServer(int port) {
		/*
		 * These are some fairly random defaults. There should be a way to override them.
		 */
		var queue = new BlockingArrayQueue<Runnable>(128, 128, 300_000);
		var executor = new QueuedThreadPool(10, 4, 10_000, queue);
		executor.setName(SiteServer.class.getSimpleName());
		executor.setDaemon(true);
		SiteThread.monitor("jetty", executor);
		server = new Server(executor);
		var connector = new ServerConnector(server, new HttpConnectionFactory());
		connector.setHost(InetAddress.getLoopbackAddress().getHostAddress());
		connector.setPort(port);
		server.addConnector(connector);
		server.setHandler(new VHostSwitch());
		SiteLaunch.profile("Jetty server is initialized.");
	}
	public SiteServer start() {
		Exceptions.sneak().run(() -> server.start());
		SiteLaunch.profile("Jetty server is started.");
		return this;
	}
}
