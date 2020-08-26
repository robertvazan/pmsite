// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite;

import java.io.*;
import java.net.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.*;
import java.util.stream.*;
import javax.servlet.http.*;
import org.apache.commons.lang3.exception.*;
import org.apache.http.client.utils.*;
import org.slf4j.*;
import com.google.common.base.*;
import com.machinezoo.hookless.*;
import com.machinezoo.hookless.prefs.*;
import com.machinezoo.hookless.servlets.*;
import com.machinezoo.hookless.util.*;
import com.machinezoo.noexception.*;
import com.machinezoo.pmsite.utils.*;
import com.machinezoo.pushmode.*;
import com.machinezoo.pushmode.dom.*;
import com.machinezoo.stagean.*;

/**
 * Base class for pages providing some common functionality.
 */
@StubDocs
@DraftApi
public class SitePage extends PushPage {
	private SiteLocation location;
	public SitePage location(SiteLocation location) {
		this.location = location;
		return this;
	}
	public SiteLocation location() {
		return location;
	}
	private SiteConfiguration site;
	public SitePage site(SiteConfiguration site) {
		this.site = site;
		return this;
	}
	public SiteConfiguration site() {
		if (site != null)
			return site;
		if (location() != null)
			return location().site();
		return null;
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
	public String language() {
		return site().language();
	}
	public String title() {
		if (template() != null && template().title() != null)
			return template().title();
		if (location() != null)
			return location().title();
		return null;
	}
	public String supertitle() {
		if (location() != null && location().parent() != null)
			return location().parent().supertitle();
		return null;
	}
	public String description() {
		if (template() != null)
			return template().description();
		return null;
	}
	protected DomElement body() {
		if (template() != null && template().body() != null)
			return template().body();
		return Html.body()
			.add(header())
			.add(main())
			.add(footer());
	}
	protected DomElement header() {
		return null;
	}
	protected DomElement main() {
		if (template() != null) {
			if (template().main() != null)
				return template().main();
			if (template().article() != null) {
				return Html.main()
					.add(template().article());
			}
		}
		return Html.main();
	}
	protected DomElement footer() {
		return null;
	}
	protected String templatePath() {
		if (location() != null)
			return location().template();
		return null;
	}
	protected SiteTemplate templateSetup() {
		return SiteTemplate.resource(getClass(), templatePath())
			.page(this);
	}
	private final Supplier<SiteTemplate> template = OwnerTrace
		.of(new ReactiveLazy<>(() -> {
			SiteReload.watch();
			if (templatePath() == null)
				return null;
			return templateSetup()
				.load();
		}))
		.parent(this)
		.tag("role", "template")
		.target();
	protected SiteTemplate template() {
		return template.get();
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
		Exceptions.log().run(() -> {
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
	private String assembleTitle() {
		if (title() != null) {
			if (supertitle() != null)
				return title() + " - " + supertitle();
			else
				return title();
		} else if (supertitle() != null)
			return supertitle();
		else if (site() != null)
			return site().title();
		else
			return null;
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
			.add(assembleTitle() == null ? null : Html.title().add(assembleTitle()))
			.add(Html.meta().name("viewport").content("width=device-width, initial-scale=1"))
			.add(description() == null ? null : Html.meta().name("description").content(description()))
			.add(canonical() == null ? null : Html.link()
				.rel("canonical")
				.href(canonical()))
			.add(icon.png180() == null ? null : Html.link()
				.rel("apple-touch-icon")
				.sizes("180x180")
				.href(icon.png180()))
			.add(icon.png16() == null ? null : Html.link()
				.rel("icon")
				.type("image/png")
				.sizes("16x16")
				.href(icon.png16()))
			.add(icon.png32() == null ? null : Html.link()
				.rel("icon")
				.type("image/png")
				.sizes("32x32")
				.href(icon.png32()))
			.add(icon.manifest() == null ? null : Html.link()
				.rel("manifest")
				.href(icon.manifest()));
	}
	private static final Logger logger = LoggerFactory.getLogger(SitePage.class);
	private boolean pageviewSent;
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
			} catch (Throwable ex) {
				/*
				 * We provide top-level exception handler in addition to binding-level handlers.
				 * This will catch exceptions from inline and other unprotected bindings
				 * as well as from application code running outside of the XML template.
				 */
				body = Html.body()
					.add(handle(ex));
			}
			return Html.html().lang(language())
				.add(head())
				.add(body);
		} catch (Throwable ex) {
			/*
			 * Since whole body() call is guarded with an exception handler,
			 * we will get here in case we cannot even produce page header.
			 * This most commonly happens if XML template parsing fails.
			 * 
			 * Ideally, we would rely on exception handling in pushmode,
			 * but that doesn't work well yet, so we make sure we handle exceptions here.
			 * 
			 * We will use handle() to give the app a chance to rethrow.
			 * By default, we will just block indefinitely, hoping the exception will go away.
			 */
			handle(ex);
			CurrentReactiveScope.block();
			return Html.html();
		} finally {
			SiteLaunch.profile("Generated first page on site {}.", host);
			if (!CurrentReactiveScope.blocked())
				SiteLaunch.profile("Generated first non-blocking page on site {}.", host);
		}
	}
	/*
	 * Bindings and perhaps other parts of the program may need to show error messages on pages.
	 * This is usually possible only in reasonable places like on top level in articles,
	 * but page CSS can style the error as overlay in other cases, so errors are theoretically allowed anywhere.
	 * 
	 * Sites base pages and individual pages can override this exception handling method.
	 * By default, we log the exception and return stack trace in development and a short message in production.
	 * It is perfectly reasonable for applications to just rethrow the exception here if they choose to.
	 */
	public DomElement handle(Throwable ex) {
		/*
		 * Allow blocking operations to throw exceptions without unnecessary logging.
		 */
		if (!CurrentReactiveScope.blocked())
			logger.error("Exception on site {}, page {}", site().uri().getHost(), Exceptions.sneak().get(() -> new URI(request().url())).getPath(), ex);
		if (SiteRunMode.get() != SiteRunMode.DEVELOPMENT) {
			/*
			 * We don't want to show stack trace in production as it may reveal secrets about the application.
			 */
			return Html.pre()
				.clazz("site-error")
				.add("This content failed to load.");
		}
		StringWriter writer = new StringWriter();
		ExceptionUtils.printRootCauseStackTrace(ex, new PrintWriter(writer));
		return Html.pre()
			.clazz("site-error")
			.add(writer.toString());
	}
}
