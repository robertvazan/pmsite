// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite;

import java.io.*;
import java.net.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import java.util.regex.*;
import java.util.stream.*;
import org.apache.commons.lang3.exception.*;
import org.apache.commons.lang3.tuple.*;
import org.apache.http.client.utils.*;
import org.slf4j.*;
import com.google.common.base.*;
import com.machinezoo.hookless.*;
import com.machinezoo.hookless.prefs.*;
import com.machinezoo.hookless.servlets.*;
import com.machinezoo.hookless.util.*;
import com.machinezoo.noexception.*;
import com.machinezoo.noexception.slf4j.*;
import com.machinezoo.pmsite.utils.*;
import com.machinezoo.pushmode.*;
import com.machinezoo.pushmode.dom.*;
import com.machinezoo.stagean.*;
import jakarta.servlet.http.*;

/**
 * Base class for pages providing some common functionality.
 */
@StubDocs
@DraftApi
public class SitePage extends PushPage {
	private SiteConfiguration site;
	public SiteConfiguration site() {
		return site;
	}
	public SitePage site(SiteConfiguration site) {
		this.site = site;
		return this;
	}
	/*
	 * The idea of dynamic subtrees is that they are not enumerable, not even lazily, contrary to standard locations.
	 * Such dynamic subtree effectively treats path parameters as query parameters.
	 */
	@DraftApi("All locations should be fully described in site's location tree already. Path subtrees should be dynamically populated location trees.")
	protected SiteLocation describe() {
		var path = request().url().getPath();
		/*
		 * This introduces reactive dependency on location tree, which causes invalidation of the page whenever any template changes.
		 * Templates however change only during development when only a few pages are open. There's no impact on production performance.
		 */
		var location = site.location(path);
		if (location == null) {
			/*
			 * It is up to debate what is the best course of action in case we don't find our own location.
			 * This can happen when dynamically generated page is removed.
			 * It can also commonly happen during development due to errors in XML templates used to build the location tree.
			 * In development, throwing will log stack trace and reactively block the page until the problem is fixed.
			 * In production, throwing ensures the problem is reported via logs.
			 */
			throw new IllegalStateException("No such location: " + request().url());
		}
		if (location.path() == null) {
			return location.clone()
				.path(path)
				.subtree(null);
		}
		return location;
	}
	private ReactiveLazy<SiteLocation> location = OwnerTrace
		.of(new ReactiveLazy<>(() -> {
			var location = describe();
			location.freeze();
			location.validate();
			return location;
		}))
		.parent(this)
		.tag("role", "location")
		.target();
	public SiteLocation location() {
		return CurrentReactiveScope.freeze(Pair.of(this, "location"), location);
	}
	public SiteFragment fragment() {
		return SiteFragment.forPage(this);
	}
	public SiteFragment fragment(String... path) {
		return fragment().nest(path);
	}
	private ReactivePreferences preferences;
	public synchronized ReactivePreferences preferences() {
		if (preferences == null)
			preferences = fragment().preferences();
		return preferences;
	}
	public SiteAnalytics analytics() {
		return SiteAnalytics.none();
	}
	public Stream<String> css() {
		return Stream.empty();
	}
	protected void widgets(SiteTemplate template) {
		SiteWidgets.register(this, template);
	}
	public DomElement expand(DomElement content) {
		var template = new SiteTemplate(content);
		widgets(template);
		return fragment().run(template::render).element();
	}
	protected DomElement body() {
		if (location().body() != null)
			return expand(location().body());
		return Html.body()
			.add(header())
			.add(main())
			.add(footer());
	}
	protected DomElement header() {
		return null;
	}
	protected DomElement main() {
		if (location().main() != null)
			return expand(location().main());
		if (location().article() != null) {
			return Html.main()
				.add(expand(location().article()));
		}
		return Html.main();
	}
	protected DomElement footer() {
		return null;
	}
	private String browserId;
	public String browserId() {
		return browserId;
	}
	private boolean browserIdIsFresh;
	private static final Pattern browserIdPattern = Pattern.compile("^v1\\.[0-9]{1,12}\\.[a-zA-Z0-9]{30,100}$");
	private static final Supplier<SecureRandom> random = Suppliers.memoize(SecureRandom::new);
	@Override
	public void serve(ReactiveServletRequest request) {
		super.serve(request);
		ExceptionLogging.log().run(() -> {
			String cookie = request().cookies().stream().filter(c -> c.getName().equals("id")).map(c -> c.getValue()).findFirst().orElse(null);
			if (cookie != null) {
				if (browserIdPattern.matcher(cookie).matches()) {
					String[] parts = cookie.split("\\.");
					browserId = parts[2];
					if (Duration.between(Instant.ofEpochSecond(Long.parseLong(parts[1])), Instant.now()).toDays() == 0)
						browserIdIsFresh = true;
				}
			}
		});
		if (browserId == null) {
			byte[] bytes = new byte[32];
			random.get().nextBytes(bytes);
			browserId = Base64.getUrlEncoder().encodeToString(bytes).replace("_", "").replace("-", "").replace("=", "");
		}
	}
	@Override
	public void serve(ReactiveServletResponse response) {
		super.serve(response);
		if (location().status() > 0)
			response.status(location().status());
		if (!browserIdIsFresh) {
			Cookie cookie = new Cookie("id", "v1." + Instant.now().getEpochSecond() + "." + browserId);
			cookie.setPath("/");
			cookie.setMaxAge((int)Duration.ofDays(2 * 365).getSeconds());
			cookie.setSecure(SiteRunMode.get() == SiteRunMode.PRODUCTION);
			response.cookies().add(cookie);
		}
	}
	protected String canonical() {
		URI root = site().uri();
		return Exceptions.sneak().get(() -> new URIBuilder(request().url()))
			.setScheme(root.getScheme())
			.setHost(root.getHost())
			.setPort(root.getPort())
			.clearParameters()
			.toString();
	}
	public String asset(String path) {
		return site().asset(path);
	}
	private String title() {
		if (location().extitle() != null)
			return location().extitle();
		if (location().title() != null) {
			if (location().supertitle().isPresent() && !location().title().contains(location().supertitle().get()))
				return location().title() + " - " + location().supertitle().get();
			else
				return location().title();
		} else if (location().supertitle().isPresent())
			return location().supertitle().get();
		else
			return site().title();
	}
	protected DomElement head() {
		SiteIcon icon = Optional.ofNullable(site().favicon()).orElse(new SiteIcon());
		return Html.head()
			.add(Html.meta().charset("UTF-8"))
			.add(css()
				.distinct()
				.map(css -> Html.link().key("style-" + css).rel("stylesheet").href(asset(css))))
			.add(Html.script()
				.id("pushmode-script")
				.src(PushScriptServlet.url())
				.async()
				.set("onerror", "setTimeout(function(){location.replace(location.href)},10000)"))
			.add(title() == null ? null : Html.title().add(title()))
			.add(Html.meta().name("viewport").content("width=device-width, initial-scale=1"))
			.add(location().description() == null ? null : Html.meta().name("description").content(location().description()))
			.add(canonical() == null
				? null
				: Html.link()
					.rel("canonical")
					.href(canonical()))
			.add(icon.png180() == null
				? null
				: Html.link()
					.rel("apple-touch-icon")
					.sizes("180x180")
					.href(icon.png180()))
			.add(icon.png16() == null
				? null
				: Html.link()
					.rel("icon")
					.type("image/png")
					.sizes("16x16")
					.href(icon.png16()))
			.add(icon.png32() == null
				? null
				: Html.link()
					.rel("icon")
					.type("image/png")
					.sizes("32x32")
					.href(icon.png32()))
			.add(icon.manifest() == null
				? null
				: Html.link()
					.rel("manifest")
					.href(icon.manifest()));
	}
	private static final Logger logger = LoggerFactory.getLogger(SitePage.class);
	private boolean pageviewSent;
	private final AtomicBoolean bodyFailing = new AtomicBoolean();
	private final AtomicBoolean documentFailing = new AtomicBoolean();
	@Override
	public DomElement document() {
		String host = site().uri().getHost();
		SiteLaunch.profile("Started generating first page on site {}.", host);
		try {
			if (!pageviewSent && !poster()) {
				pageviewSent = true;
				analytics().pageView().send();
			}
			SiteReload.watch();
			DomElement body;
			try {
				body = body();
				if (!CurrentReactiveScope.blocked() && bodyFailing.getAndSet(false))
					logger.info("Recovered from exception on page {}.", request().url());
			} catch (Throwable ex) {
				/*
				 * HTML body is the highest level element where we can still provide reactive exception handling.
				 * Anything higher cannot be safely manipulated as HTML headers can load and execute JS.
				 */
				if (!CurrentReactiveScope.blocked())
					bodyFailing.set(true);
				body = Html.body()
					.add(handle(ex));
			}
			var html = Html.html()
				.lang(location().language().orElse(null))
				.add(head())
				.add(body);
			SiteLaunch.profile("Generated first page on site {}.", host);
			if (!CurrentReactiveScope.blocked())
				SiteLaunch.profile("Generated first non-blocking page on site {}.", host);
			if (!CurrentReactiveScope.blocked() && documentFailing.getAndSet(false))
				logger.info("Recovered from exception in document() on page {}.", request().url());
			return html;
		} catch (Throwable ex) {
			/*
			 * Since whole body() call is guarded with an exception handler, we will get here in case we cannot even produce page header.
			 * This most commonly happens when XML template parsing fails.
			 * PushPage just ceases to stream HTML when it encounters an exception. We can do better.
			 * We know whether we are running in development mode. In development mode,
			 * we can reactively block until the developer fixes the presumed error in the XML template.
			 */
			if (!CurrentReactiveScope.blocked() && SiteRunMode.get() == SiteRunMode.DEVELOPMENT) {
				documentFailing.set(true);
				logger.error("Exception in document() on page {}.", request().url(), ex);
				CurrentReactiveScope.block();
			}
			throw ex;
		}
	}
	/*
	 * We show some English text when we catch exception from body(). Allow customizing it.
	 * This method also allows other customizations like showing/hiding stack traces under various circumstances.
	 * It is perfectly reasonable for applications to just rethrow the exception here if they choose to.
	 * This method may be used in other situations where exception formatting is required.
	 */
	public DomElement handle(Throwable exception) {
		Objects.requireNonNull(exception);
		if (CurrentReactiveScope.blocked()) {
			/*
			 * Don't waste time generating stack trace when reactively blocking. Don't log anything either.
			 */
			return Html.pre()
				.clazz("site-error")
				.add("Temporary error.");
		}
		/*
		 * If we show an ugly error to the user, we also want to show it to site administrator via logs.
		 * Logging is appropriate, because we are replacing the exception with fallback value, so this is the last place where it is seen.
		 * Logging is a side effect. Since this method takes an exception as a parameter,
		 * we can assume something bad definitely happened and it is safe to produce side effects.
		 */
		logger.error("Exception on page {}.", request().url(), exception);
		if (SiteRunMode.get() != SiteRunMode.DEVELOPMENT) {
			/*
			 * We don't want to show stack trace in production as it may reveal secrets about the application.
			 */
			return Html.pre()
				.clazz("site-error")
				.add("This content failed to load.");
		}
		StringWriter writer = new StringWriter();
		ExceptionUtils.printRootCauseStackTrace(exception, new PrintWriter(writer));
		return Html.pre()
			.clazz("site-error")
			.add(writer.toString());
	}
}
