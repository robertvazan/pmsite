package com.machinezoo.pmsite;

import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.*;
import java.util.stream.*;
import javax.servlet.http.*;
import com.google.common.base.*;
import com.machinezoo.hookless.servlets.*;
import com.machinezoo.noexception.*;
import com.machinezoo.pmsite.preferences.*;
import com.machinezoo.pushmode.*;
import com.machinezoo.utils.*;

public abstract class SitePage extends PushPage {
	public abstract SiteConfiguration site();
	private PreferenceStorage preferences = PreferenceStorage.memory();
	public PreferenceStorage preferences() {
		return preferences;
	}
	public SiteAnalytics analytics() {
		return SiteAnalytics.none();
	}
	public boolean noindex() {
		return false;
	}
	public Stream<String> css() {
		return Stream.of("style");
	}
	public String language() {
		return "en";
	}
	public String description() {
		return null;
	}
	protected SiteTemplate template(String name) {
		return SiteTemplate.resource(getClass(), name);
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
}
