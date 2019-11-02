// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import java.net.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.*;
import java.util.stream.*;
import javax.servlet.http.*;
import org.apache.http.client.utils.*;
import org.slf4j.*;
import com.google.common.base.*;
import com.machinezoo.hookless.*;
import com.machinezoo.hookless.servlets.*;
import com.machinezoo.noexception.*;
import com.machinezoo.pmsite.preferences.*;
import com.machinezoo.pushmode.*;
import com.machinezoo.pushmode.dom.*;

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
	private PreferenceStorage preferences = PreferenceStorage.memory();
	public PreferenceStorage preferences() {
		return preferences;
	}
	public SiteAnalytics analytics() {
		return SiteAnalytics.none();
	}
	public Stream<String> css() {
		return Stream.empty();
	}
	public String language() {
		return "en";
	}
	public String title() {
		if (template() != null && template().title() != null)
			return template().title();
		return null;
	}
	public String description() {
		if (template() != null && template().description() != null)
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
	private final ReactiveCache<SiteTemplate> template = new ReactiveCache<>(() -> {
		SiteReload.watch();
		if (templatePath() == null)
			return null;
		return templateSetup()
			.load();
	});
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
	@Override public void serve(ReactiveServletRequest request) {
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
	@Override public void serve(ReactiveServletResponse response) {
		super.serve(response);
		if (!browserIdIsFresh) {
			Cookie cookie = new Cookie("id", "v1." + Instant.now().getEpochSecond() + "." + browserId);
			cookie.setPath("/");
			cookie.setMaxAge((int)Duration.ofDays(2 * 365).getSeconds());
			cookie.setSecure(SiteRunMode.get() == SiteRunMode.PRODUCTION);
			response.cookies().add(cookie);
		}
	}
	private final Map<String, Object> locals = new HashMap<>();
	public SiteSlot slot(String name) {
		return new SiteSlot() {
			@Override public String id() {
				return name;
			}
			@Override public PreferenceStorage preferences() {
				return SitePage.this.preferences().group(name);
			}
			@SuppressWarnings("unchecked") @Override public <T> T local(String key, Supplier<T> initializer) {
				String id = name + "." + key;
				synchronized (locals) {
					Object found = locals.get(id);
					if (found == null)
						locals.put(id, found = initializer.get());
					return (T)found;
				}
			}
			@Override public SiteAnalytics analytics() {
				return SitePage.this.analytics();
			}
		};
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
	protected DomElement head() {
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
			.add(Html.title().add(title()))
			.add(Html.meta().name("viewport").content("width=device-width, initial-scale=1"))
			.add(description() == null ? null : Html.meta().name("description").content(description()))
			.add(canonical() == null ? null : Html.link()
				.rel("canonical")
				.href(canonical()));
	}
	private static final Logger logger = LoggerFactory.getLogger(SitePage.class);
	private boolean pageviewSent;
	@Override public DomElement document() {
		String host = site().uri().getHost();
		SiteLaunch.profile("Started generating first page on site {}.", host);
		try {
			if (!pageviewSent && !poster()) {
				pageviewSent = true;
				analytics().pageView().send();
			}
			SiteReload.watch();
			DomElement body = body();
			return Html.html().lang(language())
				.add(head())
				.add(body);
		} catch (Throwable ex) {
			if (!CurrentReactiveScope.blocked())
				logger.error("Exception on site {}, page {}", host, Exceptions.sneak().get(() -> new URI(request().url())).getPath());
			if (SiteRunMode.get() != SiteRunMode.PRODUCTION)
				Exceptions.log().handle(ex);
			throw ex;
		} finally {
			SiteLaunch.profile("Generated first page on site {}.", host);
			if (!CurrentReactiveScope.blocked())
				SiteLaunch.profile("Generated first non-blocking page on site {}.", host);
		}
	}
}
