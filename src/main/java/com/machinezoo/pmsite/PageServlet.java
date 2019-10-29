// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import java.nio.*;
import java.util.function.*;
import com.machinezoo.hookless.*;
import com.machinezoo.hookless.servlets.*;
import com.machinezoo.noexception.*;
import com.machinezoo.pushmode.*;
import io.micrometer.core.instrument.*;

@SuppressWarnings("serial") public class PageServlet extends ReactiveServlet {
	private final Supplier<? extends PushPage> supplier;
	public PageServlet(Supplier<? extends PushPage> supplier) {
		this.supplier = supplier;
	}
	private static final Timer timer = Metrics.timer("http.page");
	@Override public ReactiveServletResponse doGet(ReactiveServletRequest request) {
		Timer.Sample sample = CurrentReactiveScope.pin("timer", () -> Timer.start(Clock.SYSTEM));
		SiteLaunch.profile("Page servlet started processing the first request.");
		try {
			PushPage page = CurrentReactiveScope.pin("page", () -> {
				PushPage created = supplier.get();
				PagePool.instance().add(created);
				created.serve(request);
				return created;
			});
			ReactiveServletResponse response = CurrentReactiveScope.pin("response", () -> {
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
			PageFrame frame = page.frame(0);
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
		ReactiveServletResponse response = new ReactiveServletResponse();
		response.headers().put("Content-Type", "text/html; charset=utf-8");
		response.headers().put("Cache-Control", "no-cache, no-store");
		return response;
	}
}
