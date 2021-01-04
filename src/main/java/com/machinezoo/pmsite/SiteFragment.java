// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite;

import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.Base64;
import java.util.function.*;
import java.util.prefs.*;
import java.util.regex.*;
import java.util.stream.*;
import com.machinezoo.hookless.prefs.*;
import com.machinezoo.noexception.*;
import com.machinezoo.pmsite.utils.*;
import com.machinezoo.pushmode.dom.*;
import com.machinezoo.stagean.*;

/*
 * This class is a local equivalent of SitePage and it should provide the same features as SitePage/SiteConfiguration.
 * It should work just fine outside of SitePage's event loop, asynchronously, and also in static context,
 * so that generated content can be globally cached and displayed in multiple pages.
 * That's why SitePage/SiteConfiguration references are optional (although recommended).
 * SiteConfiguration is however required when location is specified as location alone wouldn't make sense.
 * This is however a light requirement as most apps have only one SiteConfiguration.
 * 
 * Several kinds of fragments are supported:
 * - page: given location and user IDs
 * - user: given only user ID
 * - location: given only location ID
 * - global: given neither location nor user IDs
 * 
 * All types of fragments support named nested fragments.
 * 
 * SiteFragment does not allow storing any state on its own. It can only serve as a key into existing storage.
 * 
 * SiteFragment can be passed via parameter, but it's more practical to make it available via thread-local variable.
 * This has the downside that called code has to consider the possibility that no SiteFragment has been provided.
 * We will solve that by providing a temporary SiteFragment that tries to provide fallbacks for all operations.
 * Code that really requires real SiteFragment can use separate method returning Optional.
 */
/**
 * Provides hierarchical ID to widgets and collects widget output.
 */
@StubDocs
@DraftApi("analytics event IDs, CSS/JS dependencies, custom headers, error handler, asset links")
public class SiteFragment {
	/*
	 * Null in global and temporary fragments. Optional in user fragments.
	 */
	private final SiteConfiguration site;
	public SiteConfiguration site() {
		return site;
	}
	/*
	 * Normally page URL's path, but pages can customize what they consider location.
	 * Null in user, global, and temporary fragments.
	 */
	private final String location;
	public String location() {
		return location;
	}
	/*
	 * This is usually browserId, but it can be also an ID of a logged in user or some other user identifier.
	 * Null in location, global, and temporary fragments.
	 */
	private final String user;
	public String user() {
		return user;
	}
	/*
	 * Null in user, location, global, and temporary fragments. Optional in page fragments.
	 */
	private final SitePage page;
	public SitePage page() {
		return page;
	}
	/*
	 * Null only in temporary fragments.
	 */
	private final String[] path;
	private static final String[] ROOT = new String[0];
	public String[] path() {
		return path.clone();
	}
	private SiteFragment(SiteConfiguration site, String location, String user, SitePage page, String[] path) {
		this.location = location;
		this.user = user;
		this.site = site;
		this.page = page;
		this.path = path;
	}
	/*
	 * Some parameters of these named constructors are allowed to be null.
	 * The returned fragment then has correspondingly altered meaning.
	 */
	public static SiteFragment temporary() {
		return new SiteFragment(null, null, null, null, null);
	}
	public static SiteFragment global() {
		return new SiteFragment(null, null, null, null, ROOT);
	}
	public static SiteFragment forSite(SiteConfiguration site) {
		return new SiteFragment(site, null, null, null, ROOT);
	}
	public static SiteFragment forLocation(SiteConfiguration site, String location) {
		if (location != null)
			Objects.requireNonNull(site);
		return new SiteFragment(site, location, null, null, ROOT);
	}
	public static SiteFragment forLocation(SiteLocation location) {
		if (location == null)
			return global();
		Objects.requireNonNull(location.site());
		Objects.requireNonNull(location.path());
		return new SiteFragment(location.site(), location.path(), null, null, ROOT);
	}
	public static SiteFragment forUser(SiteConfiguration site, String user) {
		return new SiteFragment(site, null, user, null, ROOT);
	}
	public static SiteFragment forUser(String user) {
		return new SiteFragment(null, null, user, null, ROOT);
	}
	public static SiteFragment forPage(SiteConfiguration site, String location, String user) {
		if (location != null)
			Objects.requireNonNull(site);
		return new SiteFragment(site, location, user, null, ROOT);
	}
	public static SiteFragment forPage(SitePage page) {
		Objects.requireNonNull(page.site());
		var path = page.request().url().getPath();
		return new SiteFragment(page.site(), path, page.browserId(), page, ROOT);
	}
	public static SiteFragment forClass(Class<?> clazz) {
		if (clazz.isArray())
			throw new IllegalArgumentException();
		return new SiteFragment(null, null, null, null, clazz.getName().split("\\."));
	}
	public static SiteFragment forPackage(Class<?> clazz) {
		var pkg = clazz.getPackageName();
		if (pkg.isEmpty())
			return global();
		return new SiteFragment(null, null, null, null, pkg.split("\\."));
	}
	/*
	 * There's no equivalent parent() method, because fragment code should not try to escape its context.
	 * There might be no sensible parent at all.
	 * 
	 * We allow any non-empty fragment name, so that widgets can use labels and other plain text as fragment name.
	 */
	public SiteFragment nest(String name) {
		if (name.isEmpty())
			throw new IllegalArgumentException();
		if (path == null)
			return this;
		var child = Arrays.copyOf(path, path.length + 1);
		child[path.length] = name;
		return new SiteFragment(site, location, user, page, child);
	}
	public SiteFragment nest(String... path) {
		var descendant = this;
		for (var name : path)
			descendant = descendant.nest(name);
		return descendant;
	}
	public SiteFragment forUser() {
		return new SiteFragment(site, null, user, null, path);
	}
	public SiteFragment forLocation() {
		return new SiteFragment(site, location, null, null, path);
	}
	private static final ThreadLocal<SiteFragment> current = new ThreadLocal<>();
	public CloseableScope open() {
		var outer = current.get();
		var prefsScope = SitePreferences.open(new ReactivePreferencesFactory() {
			@Override
			public ReactivePreferences systemRoot() {
				return SitePreferences.asSystem(forLocation().preferences());
			}
			@Override
			public ReactivePreferences userRoot() {
				return SitePreferences.asUser(preferences());
			}
		});
		current.set(this);
		return () -> {
			current.set(outer);
			prefsScope.close();
		};
	}
	/*
	 * This is a frequently used API. Let's define run/supply in addition to try-with-resources open().
	 * Returning 'this' from run() allows for neat fluent one-liners.
	 */
	public SiteFragment run(Runnable runnable) {
		try (var scope = open()) {
			runnable.run();
		}
		return this;
	}
	public <T> T supply(Supplier<T> supplier) {
		try (var scope = open()) {
			return supplier.get();
		}
	}
	/*
	 * There are two methods to get current fragment. One allows checking for null and the other always returns non-null result.
	 * The one returning non-null result gets the shorter name, because it is expected to be used more often.
	 */
	public static Optional<SiteFragment> current() {
		return Optional.ofNullable(current.get());
	}
	public static SiteFragment get() {
		return current().orElseGet(SiteFragment::temporary);
	}
	public static class Key {
		private final SiteConfiguration site;
		private final String location;
		private final String user;
		private final SitePage page;
		private final String[] path;
		private Key(SiteConfiguration site, String location, String user, SitePage page, String[] path) {
			this.site = site;
			this.location = location;
			this.user = user;
			this.page = page;
			this.path = path;
		}
		@Override
		public boolean equals(Object obj) {
			if (!(obj instanceof Key))
				return false;
			Key other = (Key)obj;
			return site == other.site
				&& Objects.equals(location, other.location)
				&& Objects.equals(user, other.user)
				&& page == other.page
				&& Arrays.equals(path, other.path);
		}
		@Override
		public int hashCode() {
			return Objects.hash(location, user, site, page, Arrays.hashCode(path));
		}
	}
	public Key key() {
		return new Key(site, location, user, page, path);
	}
	public Key key(String... path) {
		return nest(path).key();
	}
	public static SiteFragment forKey(Key key) {
		return new SiteFragment(key.site, key.location, key.user, key.page, key.path);
	}
	/*
	 * We could just hash the entire path to produce short, unique, and secure element ID.
	 * We however want the IDs to be readable to ease debugging, even if it creates small security risk.
	 * The security risk involves inadvertent exposure of information,
	 * but then fragment IDs are usually either constants, user-visible labels or other content, or non-secret internal IDs.
	 */
	public String elementId() {
		/*
		 * We don't care about the degenerate case. Applications shouldn't request element ID for root fragments.
		 * If temporary fragments are used, it means the generated element ID wouldn't be used anywhere anyway.
		 */
		if (path == null || path.length == 0)
			return "/";
		var out = new StringBuilder();
		for (var name : path) {
			/*
			 * We will use slash as a separator, because it looks good when inspecting generated HTML.
			 * Its downside is that it is common in natural text and in some identifiers,
			 * but it's easy to escape by just doubling it as we don't allow empty fragment names.
			 * Pipe would require escaping less often, but it does not look that good
			 * and it might be given special meaning when the element ID is embedded in some code.
			 */
			if (out.length() != 0)
				out.append('/');
			for (var c : name.toCharArray()) {
				switch (c) {
				case ' ':
					/*
					 * Spaces are not allowed in element IDs. Underscores look similar and they are rarely used,
					 * so they wouldn't need to be escaped as often as dashes.
					 */
					out.append('_');
					break;
				case '/':
					/*
					 * Escape slash by doubling it. It looks better than tilde escaping.
					 * It is safe, because we don't allow empty fragment names.
					 */
					out.append('/');
					out.append('/');
					break;
				case '~':
				case '_':
					/*
					 * Escape our special characters. Tilde is a good escape character,
					 * because it is ASCII, rare in natural text and in identifiers, and it is usually without special meaning.
					 */
					out.append('~');
					out.append(c);
					break;
				default:
					if (!Character.isDefined(c) || Character.isSurrogate(c) || Character.isISOControl(c)) {
						/*
						 * Escape all undefined, surrogate, and control characters.
						 * The spec only requires escaping of selected whitespace characters,
						 * but we prefer to be safe and escape everything that could cause trouble.
						 */
						out.append('~');
						out.append(Integer.toHexString(c));
						out.append('.');
					} else
						out.append(c);
					break;
				}
			}
		}
		return out.toString();
	}
	public String elementId(String... path) {
		return nest(path).elementId();
	}
	private static final Pattern prefsNodeRe = Pattern.compile("[ -~&&[^|~^]]*");
	private String encodePrefsNode(String name) {
		/*
		 * Node name permits all characters except slash, but linux implementation encodes the entire name
		 * if just one character is slash, dot, or underscore, which are very common characters in natural text and identifiers.
		 * We will replace them with less frequent characters to minimize this effect.
		 */
		if (name.length() <= Preferences.MAX_NAME_LENGTH && prefsNodeRe.matcher(name).matches())
			return name.replace('/', '|').replace('.', '^').replace('_', '~');
		/*
		 * Otherwise fall back to base64-encoded hash of the name.
		 */
		byte[] hash = Exceptions.sneak().get(() -> MessageDigest.getInstance("SHA-256")).digest(name.getBytes(StandardCharsets.UTF_8));
		return Base64.getUrlEncoder().encodeToString(hash).replace("_", "").replace("-", "").replace("=", "");
	}
	/*
	 * We could have a field in SitePage, but it's cleaner to separate SiteFragment and SitePage implementations.
	 * It might be also more efficient if many pages do not use preferences.
	 */
	private static final Map<SitePage, ReactivePreferences> transients = Collections.synchronizedMap(new WeakHashMap<>());
	/*
	 * Preferences are likely to be accessed repeatedly, so let's cache them.
	 */
	private ReactivePreferences preferences;
	public synchronized ReactivePreferences preferences() {
		if (preferences != null)
			return preferences;
		if (path == null)
			return preferences = SitePreferences.memory();
		ReactivePreferences prefs = site != null ? site.preferences() : ReactivePreferences.userRoot();
		/*
		 * Give users their own node, so that the numerous user IDs don't swamp root node's child list.
		 * This will also protect us from hacking attempts via creative user IDs.
		 */
		if (user != null)
			prefs = prefs.node("users").node(encodePrefsNode(user));
		if (location != null) {
			/*
			 * We wouldn't differentiate sites by protocol since everything should be HTTPS anyway.
			 * We will include port though just in case the app contains several sites exposed on different ports.
			 */
			var authority = site().uri().getAuthority();
			/*
			 * We will not create nested nodes for locations containing slashes.
			 * Node hierarchy is more predictable this way and we are spared of the hassle of parsing the location string.
			 * 
			 * Turn "/anything" to "anything" and vice versa. Since location is usually the URL path,
			 * this will result in node name typically not starting with slash,
			 * which results in nicer directory names under default linux implementation of Preferences.
			 */
			var slug = location.startsWith("/") ? location.substring(1) : "/" + location;
			prefs = prefs.node(encodePrefsNode(authority)).node(encodePrefsNode(slug));
		}
		/*
		 * Combine with transient preferences that are specific to particular page view.
		 * Store any loaded preferences, so that they don't change after initial read.
		 * This prevents pages from influencing each other.
		 */
		prefs = SitePreferences.subtree(prefs);
		if (page != null)
			prefs = SitePreferences.stable(transients.computeIfAbsent(page, p -> SitePreferences.memory()), prefs);
		/*
		 * Freeze the returned preferences, so that app code can assume they wouldn't change in the background while the code is running.
		 * The freezing wrapper is stateless. It will work even if this SiteFragment is re-created.
		 * This must be done before path subtree is constructed, so that path becomes part of the freeze key.
		 */
		prefs = SitePreferences.freezing(prefs);
		if (path.length > 0) {
			for (var name : path)
				prefs = prefs.node(encodePrefsNode(name));
			prefs = SitePreferences.subtree(prefs);
		}
		if (site != null)
			prefs = site.intercept(prefs);
		return preferences = prefs;
	}
	/*
	 * Input widgets expose two kinds of values: rendered HTML and the input data.
	 * In order to support short one-liners for requesting input from user,
	 * the rendered HTML will be written into current thread-local instance of this class,
	 * so that the one-liner can use its return value for input data obtained from the user.
	 * 
	 * The only supported output is DomFragment, but it could be more in the future (CSS/JS dependencies, custom HTML headers).
	 */
	private final DomFragment content = new DomFragment();
	public DomFragment content() {
		return content;
	}
	/*
	 * Convenience method for cases when caller knows that only one element was added.
	 */
	public DomElement element() {
		if (content.children().size() > 1)
			throw new IllegalStateException();
		var child = content.children().get(0);
		if (!(child instanceof DomElement))
			throw new IllegalStateException();
		return (DomElement)child;
	}
	/*
	 * Code adding content to the fragment will be frequent. It makes sense to provide it with helper output methods.
	 * It is tempting to make these methods static to shorten the calling code even further,
	 * but we want to give calling code the flexibility of choosing how to obtain the SiteFragment reference.
	 */
	public SiteFragment add(DomContent child) {
		content.add(child);
		return this;
	}
	public <C extends DomContent> SiteFragment add(Collection<C> children) {
		content.add(children);
		return this;
	}
	public <C extends DomContent> SiteFragment add(Stream<C> children) {
		content.add(children);
		return this;
	}
	public SiteFragment add(String text) {
		content.add(text);
		return this;
	}
	/*
	 * Finally, we need an easy way to unpack fragment's content into DOM tree or into another fragment.
	 * If SiteFragment is extended with other kinds of output, these methods will become more useful.
	 */
	public void render(DomContainer target) {
		target.add(content);
	}
	public void render() {
		SiteFragment.get().add(content);
	}
}
