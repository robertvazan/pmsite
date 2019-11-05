// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.servlet.http.*;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.*;
import org.eclipse.jetty.util.*;
import org.eclipse.jetty.util.thread.*;
import com.machinezoo.noexception.*;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;

/*
 * Simple wrapper around embedded jetty. This code assumes that HTTPS is provided by reverse proxy (Apache, nginx, ...).
 */
public class SiteServer {
	private final Server server;
	private final Map<String, Supplier<Handler>> lazy = new HashMap<>();
	private final Map<String, Handler> handlers = new HashMap<>();
	public SiteServer(int port) {
		/*
		 * These are some fairly random defaults. There should be a way to override them.
		 */
		BlockingQueue<Runnable> queue = new BlockingArrayQueue<>(128, 128, 300_000);
		QueuedThreadPool executor = new QueuedThreadPool(10, 4, 10_000, queue);
		executor.setName(SiteServer.class.getSimpleName());
		executor.setDaemon(true);
		SiteThread.monitor("jetty", executor);
		server = new Server(executor);
		ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory());
		connector.setHost(InetAddress.getLoopbackAddress().getHostAddress());
		connector.setPort(port);
		server.addConnector(connector);
		server.setHandler(new SwitchHandler());
		SiteLaunch.profile("Jetty server is initialized.");
	}
	public SiteServer site(URI uri, Supplier<SiteConfiguration> supplier) {
		lazy.put(uri.getHost(), () -> supplier.get()
			.uri(uri)
			.completeMappings()
			.handler());
		/*
		 * Force site loading in the background even if there are no requests for it.
		 * This will run all the initialization code and thus throw exceptions in case of configuration errors.
		 */
		SiteLaunch.delay(() -> handler(uri.getHost()));
		return this;
	}
	public SiteServer start() {
		Exceptions.sneak().run(() -> server.start());
		SiteLaunch.profile("Jetty server is started.");
		return this;
	}
	private static Timer timer;
	private class SwitchHandler extends AbstractHandler {
		@Override public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) {
			/*
			 * Lazily initialize the timer to avoid loading micrometer before we start listening.
			 * Use plain synchronization instead of Suppliers.memoize() to avoid loading reflection-related classes.
			 */
			if (timer == null)
				timer = Metrics.timer("http.request");
			Timer.Sample sample = Timer.start(Clock.SYSTEM);
			String hostname = baseRequest.getServerName();
			SiteLaunch.profile("Received first request for site {}.", hostname);
			Handler handler = handler(hostname);
			if (handler != null) {
				Exceptions.sneak().run(() -> handler.handle(target, baseRequest, request, response));
				sample.stop(timer);
			}
		}
	}
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
			Supplier<Handler> supplier = lazy.get(hostname);
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
}
