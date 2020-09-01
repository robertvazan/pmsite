// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite;

import static java.util.stream.Collectors.*;
import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.security.*;
import java.time.*;
import java.time.format.*;
import java.time.temporal.*;
import java.util.*;
import java.util.function.*;
import java.util.regex.*;
import java.util.stream.*;
import javax.xml.parsers.*;
import org.apache.commons.io.*;
import org.apache.commons.math3.util.*;
import org.xml.sax.*;
import com.machinezoo.hookless.servlets.*;
import com.machinezoo.noexception.*;
import com.machinezoo.pmsite.utils.*;
import com.machinezoo.pushmode.dom.*;
import com.machinezoo.stagean.*;
import it.unimi.dsi.fastutil.objects.*;

/*
 * Locations carry several kinds of information:
 * - relations to other locations
 * - URL matchers
 * - request handlers
 * - page/content metadata
 * 
 * Some core metadata fields are predefined here. Applications are free to extend this class to add more metadata.
 * Libraries can offer interfaces with default methods as pluggable mixins.
 */
/**
 * Static equivalent of {@link SitePage}.
 * While {@link SitePage} is instantiated for every page view, {@code SiteLocation} is defined only once per URL.
 */
@StubDocs
@DraftApi
@DraftCode("XML schema would ease editing")
public class SiteLocation implements Cloneable {
	/*
	 * The downside of extension through inheritance is that fluent methods of derived class must be called first
	 * and reads require a cast. We will try to alleviate this problem with convenient, although somewhat slower, casting method.
	 * This will also work for interfaces.
	 */
	public <T> T as(Class<T> clazz) {
		return clazz.cast(this);
	}
	/*
	 * Locations are mutable for ease of editing, which carries the risk of accidental modification.
	 * We will therefore freeze generated location trees to avoid such accidents.
	 * Volatile to ensure freezing is observed by other threads.
	 */
	private volatile boolean frozen;
	private void modify() {
		if (frozen)
			throw new IllegalStateException("Cannot modify frozen location.");
	}
	/*
	 * Keys are used to merge child lists. Children with the same key will be merged.
	 */
	private String key;
	public String key() {
		return key;
	}
	public SiteLocation key(String key) {
		modify();
		this.key = key;
		return this;
	}
	/*
	 * Locations form a tree, in which parents can provide defaults and context for child locations.
	 */
	private SiteConfiguration site;
	public SiteConfiguration site() {
		return site;
	}
	public SiteLocation site(SiteConfiguration site) {
		modify();
		this.site = site;
		return this;
	}
	/*
	 * We do not automatically add symmetric parent-child relation, because the location could be synthetic,
	 * in which case it is not part of the location tree.
	 */
	private SiteLocation parent;
	public SiteLocation parent() {
		return parent;
	}
	public SiteLocation parent(SiteLocation parent) {
		modify();
		this.parent = parent;
		return this;
	}
	private List<SiteLocation> children = new ArrayList<>();
	public List<SiteLocation> children() {
		/*
		 * Do not add immutable wrapper, because freezing already replaces child list with an immutable collection.
		 */
		return children;
	}
	/*
	 * Here we break off from standard method naming, because add() is such a common operation.
	 */
	public SiteLocation add(SiteLocation child) {
		modify();
		/*
		 * Tolerate null, so that we can make constructs like add(condition ? null : new SiteLocation()...).
		 */
		if (child != null)
			children.add(child);
		return this;
	}
	public SiteLocation children(List<SiteLocation> children) {
		modify();
		children = new ArrayList<>();
		/*
		 * Ensure this operation is equivalent to adding children one at a time.
		 */
		for (var child : children)
			add(child);
		return this;
	}
	public List<SiteLocation> ancestors() {
		return parent != null ? parent.ancestorsAndSelf() : Collections.emptyList();
	}
	public List<SiteLocation> ancestorsAndSelf() {
		List<SiteLocation> ancestors = new ArrayList<>();
		for (SiteLocation ancestor = this; ancestor != null; ancestor = ancestor.parent())
			ancestors.add(ancestor);
		/*
		 * Order from root to self. This is the usual required order, for example in breadcrumbs.
		 */
		Collections.reverse(ancestors);
		return ancestors;
	}
	public Stream<SiteLocation> descendants() {
		return children().stream().flatMap(SiteLocation::descendantsAndSelf);
	}
	public Stream<SiteLocation> descendantsAndSelf() {
		return Stream.concat(Stream.of(this), descendants());
	}
	/*
	 * Synthetic locations exist outside of the main location tree.
	 * They may be however derived from a location that is part of the tree.
	 * The original location may be linked via this field. This field is null in tree locations.
	 */
	private SiteLocation base;
	public SiteLocation base() {
		return base;
	}
	public SiteLocation base(SiteLocation base) {
		modify();
		this.base = base;
		return this;
	}
	/*
	 * Virtual locations do not match any URL and they are not mapped to any handler.
	 * They serve as a parent for other locations, providing defaults and context.
	 * They can however declare exact path matcher to serve as base for relative paths in children.
	 * 
	 * We require explicit flagging of virtual locations rather than defaulting to them
	 * when no URL matcher is configured in order to minimize configuration errors.
	 * 
	 * Use of virtual locations is discouraged, because they may result in confusing navigation.
	 */
	private boolean virtual;
	public boolean virtual() {
		return virtual;
	}
	public SiteLocation virtual(boolean virtual) {
		modify();
		this.virtual = virtual;
		return this;
	}
	/*
	 * Every non-virtual location has exactly one URL matcher, which must be unique in the location tree.
	 * Resources accessible via multiple URLs need multiple location objects (except for simple aliases as explained below).
	 * 
	 * The most common URL matcher is exact path. Even sites with dynamic content are usually dynamically generating their location tree
	 * rather than using more complex URL matchers, which has the advantage of generating correct sitemaps, breadcrumbs, and menus.
	 * Exact path can be configured also for virtual locations to serve as base for relative paths in child locations.
	 * 
	 * Exact path matchers have a few special features that ease configuration of sites with lots of individual pages:
	 * - Root location path defaults to /.
	 * - Location paths without leading slash are taken to be relative to parent location.
	 * - Location path defaults to template filename (without package and extension) if XML template is provided.
	 * - For resources, location path defaults to resource filename with extension but without package.
	 * - Virtual locations by default inherit path from their parent in order to aid path resolution in their children.
	 * - Aliases are a convenient way to 301 several other paths to this location.
	 * 
	 * Relative paths are resolved when location tree is built.
	 * Paths are in decoded form. They are decoded/encoded to URI paths on I/O.
	 */
	private String path;
	public String path() {
		return path;
	}
	public SiteLocation path(String path) {
		modify();
		this.path = path;
		return this;
	}
	/*
	 * Aliases generate 301 redirects to the primary path. Without leading slash, they are taken to be relative to the primary path.
	 */
	private List<String> aliases = new ArrayList<>();
	public List<String> aliases() {
		return aliases;
	}
	public SiteLocation alias(String alias) {
		Objects.requireNonNull(alias);
		modify();
		aliases.add(alias);
		return this;
	}
	public SiteLocation aliases(List<String> aliases) {
		modify();
		aliases = new ArrayList<>();
		/*
		 * Ensure this operation is equivalent to adding aliases one at a time.
		 */
		for (var alias : aliases)
			alias(alias);
		return this;
	}
	/*
	 * Single location object may also handle requests for several URL paths.
	 * Currently we only support simple subtree mapping.
	 * Aliases make no sense for subtree mappings, because there is no single path to target in a redirect.
	 * We also avoid relative paths in subtrees, because they would be just confusing in those few cases where they would be used.
	 */
	private String subtree;
	public String subtree() {
		return subtree;
	}
	public SiteLocation subtree(String subtree) {
		modify();
		if (subtree != null && (!subtree.startsWith("/") || !subtree.endsWith("/")))
			throw new IllegalArgumentException("Subtree path must start and end with '/': " + subtree);
		this.subtree = subtree;
		return this;
	}
	/*
	 * Non-virtual location is always mapped to a one of several possible request handlers:
	 * - SitePage class/constructor
	 * - static content from resources
	 * - 30x redirect page/subtree/pattern
	 * - error status (often 410 or 404)
	 * - any reactive servlet
	 * 
	 * Other request handlers can be defined as reactive servlets. More predefined handlers can be added in the future.
	 * 
	 * Page can be specified via supplier (constructor), page class, or inherited viewer.
	 * Since viewer is always available, page is the default request handler if nothing else is specified.
	 * 
	 * Here we deviate from naming pattern. We want to make both ways of specifying the page equivalent.
	 */
	private Class<? extends SitePage> clazz;
	public Class<? extends SitePage> clazz() {
		return clazz;
	}
	public SiteLocation page(Class<? extends SitePage> clazz) {
		modify();
		this.clazz = clazz;
		return this;
	}
	private Supplier<SitePage> constructor;
	public Supplier<SitePage> constructor() {
		return constructor;
	}
	public SiteLocation page(Supplier<SitePage> constructor) {
		modify();
		this.constructor = constructor;
		return this;
	}
	private Supplier<SitePage> viewer;
	public Supplier<SitePage> viewer() {
		return viewer;
	}
	public SiteLocation viewer(Supplier<SitePage> viewer) {
		modify();
		this.viewer = viewer;
		return this;
	}
	/*
	 * Status is not really a standalone request handler. It needs page for actual rendering.
	 * It is however applied by default SitePage serve() method implementation, so it can be used on its own.
	 * SiteConfiguration likely wants to intercept locations with status codes.
	 * It is also used to differentiate the several redirect variants and no page is generated in that case.
	 */
	private int status;
	public int status() {
		return status;
	}
	public SiteLocation status(int status) {
		modify();
		this.status = status;
		return this;
	}
	public SiteLocation gone() {
		return status(410);
	}
	/*
	 * The most general redirect computes destination URL from request object, possibly reactively blocking.
	 * Several convenience methods are provided for common simpler cases.
	 * Per spec, destination may be any URL, absolute or relative. It is resolved by client.
	 */
	private Function<ReactiveServletRequest, URI> redirect;
	public Function<ReactiveServletRequest, URI> redirect() {
		return redirect;
	}
	private static final int[] REDIRECT_CODES = new int[] { 301, 302, 303, 307, 308 };
	public SiteLocation redirectRequest(int status, Function<ReactiveServletRequest, URI> redirect) {
		modify();
		if (redirect == null && status != 0 || redirect != null && !Arrays.stream(REDIRECT_CODES).anyMatch(s -> s == status))
			throw new IllegalArgumentException();
		this.redirect = redirect;
		this.status = status;
		return this;
	}
	public SiteLocation redirectRequest(Function<ReactiveServletRequest, URI> redirect) {
		return redirectRequest(redirect != null ? 301 : 0, redirect);
	}
	/*
	 * The rewrite rule receives full URL that it can parse to get path or path+query.
	 */
	public SiteLocation redirect(int status, Function<URI, URI> redirect) {
		return redirectRequest(status, rq -> redirect.apply(rq.url()));
	}
	public SiteLocation redirect(Function<URI, URI> redirect) {
		return redirect(redirect != null ? 301 : 0, redirect);
	}
	public SiteLocation redirect(int status, URI location) {
		return redirect(status, u -> location);
	}
	public SiteLocation redirect(URI location) {
		return redirect(location != null ? 301 : 0, location);
	}
	/*
	 * Methods taking String parameter receive encoded URL. Even though location is usually a path
	 * that could be accepted in decoded form, it may be also an absolute URL, which requires encoding.
	 */
	public SiteLocation redirect(int status, String location) {
		return redirect(status, location != null ? URI.create(location) : null);
	}
	public SiteLocation redirect(String location) {
		return redirect(location != null ? 301 : 0, location);
	}
	public SiteLocation redirectTree(int status, URI prefix) {
		if (prefix == null)
			return redirect(status, (URI)null);
		if (!prefix.getPath().startsWith("/") || !prefix.getPath().endsWith("/"))
			throw new IllegalArgumentException("Target URL prefix must be an absolute path ending with '/'.");
		return redirect(status, u -> {
			var at = u.getPath();
			if (subtree != null) {
				if (at.startsWith(subtree))
					return prefix.resolve(at.substring(subtree.length()));
				else
					return prefix;
			}
			if (path != null && path.startsWith("/"))
				return prefix.resolve(path.substring(1));
			return prefix;
		});
	}
	public SiteLocation redirectTree(URI prefix) {
		return redirectTree(prefix != null ? 301 : 0, prefix);
	}
	public SiteLocation redirectTree(int status, String prefix) {
		return redirectTree(status, prefix != null ? URI.create(prefix) : null);
	}
	public SiteLocation redirectTree(String prefix) {
		return redirectTree(prefix != null ? 301 : 0, prefix);
	}
	private ReactiveServlet servlet;
	public ReactiveServlet servlet() {
		return servlet;
	}
	public SiteLocation servlet(ReactiveServlet servlet) {
		modify();
		this.servlet = servlet;
		return this;
	}
	/*
	 * For convenience, we want to reference resources (templates, static assets) with relative paths.
	 * We therefore need base resource package.
	 * 
	 * Resource package can be configured explicitly or it can be derived automatically, by default in this order:
	 * - package of page class if provided
	 * - resource package of parent location
	 * - site package (per site().getClass())
	 * 
	 * Relative resource package is resolved against automatically determined package path.
	 * During resolution, the base package path is treated as a directory path.
	 * Resolved resource path is normalized to end with a slash.
	 */
	private String resources;
	public String resources() {
		return resources;
	}
	public SiteLocation resources(String resources) {
		modify();
		this.resources = resources;
		return this;
	}
	private static String packagePath(Class<?> clazz) {
		return "/" + clazz.getPackageName().replace('.', '/') + "/";
	}
	public SiteLocation resources(Class<?> clazz) {
		return resources(packagePath(clazz));
	}
	/*
	 * Asset is a resource that is exposed as is via servlet without any transformation.
	 * Asset path is resolved to absolute path when location tree is constructed.
	 */
	private String asset;
	public String asset() {
		return asset;
	}
	public SiteLocation asset(String asset) {
		modify();
		this.asset = asset;
		return this;
	}
	/*
	 * Templates are loaded and their content merged into the location object, so template reference becomes useless afterwards.
	 * We cannot however load the template while creating the location, because we don't know resource package by that time.
	 * Templates therefore must be loaded when location tree is being constructed and ancestor locations have been already processed.
	 * Template path then loses value. Resolved template path is kept in case it might be useful for some app code.
	 */
	private String template;
	public String template() {
		return template;
	}
	public SiteLocation template(String template) {
		modify();
		this.template = template;
		return this;
	}
	/*
	 * Page/content metadata fields are below. Derived classes can add more.
	 * 
	 * Priority can be configured for XML sitemaps.
	 * Null value causes parent's value to be inherited. Empty value disables sitemap priority.
	 */
	private OptionalDouble priority = OptionalDouble.empty();
	public OptionalDouble priority() {
		return priority;
	}
	public SiteLocation priority(OptionalDouble priority) {
		Objects.requireNonNull(priority);
		modify();
		if (priority.isPresent())
			if (priority.getAsDouble() < 0 || priority.getAsDouble() > 1)
				throw new IllegalArgumentException();
		this.priority = priority;
		return this;
	}
	public SiteLocation priority(double priority) {
		return priority(OptionalDouble.of(priority));
	}
	/*
	 * Null value is overwritten with inherited value. Empty Optional forces no language.
	 */
	private Optional<String> language;
	public Optional<String> language() {
		return language;
	}
	public SiteLocation language(Optional<String> language) {
		modify();
		this.language = language;
		return this;
	}
	/*
	 * Title should make sense when standing alone, separate from supertitle or site title.
	 * Site title or supertitle should be treated as a perspective on topic identified by title.
	 */
	private String title;
	public String title() {
		return title;
	}
	public SiteLocation title(String title) {
		modify();
		this.title = title;
		return this;
	}
	/*
	 * Supertitle is usually identical to site title, but it can be modified for subtrees of locations.
	 * Null value is overwritten with inherited value. Empty Optional forces no supertitle.
	 */
	private Optional<String> supertitle;
	public Optional<String> supertitle() {
		return supertitle;
	}
	public SiteLocation supertitle(Optional<String> supertitle) {
		modify();
		this.supertitle = supertitle;
		return this;
	}
	/*
	 * External title is usually composed from title and supertitle, but it is possible to override it here.
	 */
	private String extitle;
	public String extitle() {
		return extitle;
	}
	public SiteLocation extitle(String extitle) {
		modify();
		this.extitle = extitle;
		return this;
	}
	/*
	 * Breadcrumb title is as short as possible while still making sense in the context of ancestor breadcrumb titles.
	 * Breadcrumb titles are of course used to build breadcrumb navigation, but they can be used to build hierarchical menus.
	 * Breadcrumb defaults to title.
	 */
	private String breadcrumb;
	public String breadcrumb() {
		return breadcrumb;
	}
	public SiteLocation breadcrumb(String breadcrumb) {
		modify();
		this.breadcrumb = breadcrumb;
		return this;
	}
	/*
	 * Meta description. It should be left null most of the time. Useful for rare pages that have next to no text on them.
	 */
	private String description;
	public String description() {
		return description;
	}
	public SiteLocation description(String description) {
		modify();
		this.description = description;
		return this;
	}
	/*
	 * Article publishing/update timestamp. It makes sense for some content.
	 */
	private Instant published;
	public Instant published() {
		return published;
	}
	public SiteLocation published(Instant published) {
		modify();
		this.published = published;
		return this;
	}
	private Instant updated;
	public Instant updated() {
		return updated;
	}
	public SiteLocation updated(Instant updated) {
		modify();
		this.updated = updated;
		return this;
	}
	/*
	 * Site content is provided in the form of whole elements, so that HTML attributes can be set on them.
	 */
	private DomElement body;
	public DomElement body() {
		return body;
	}
	public SiteLocation body(DomElement body) {
		modify();
		this.body = body;
		return this;
	}
	private DomElement main;
	public DomElement main() {
		return main;
	}
	public SiteLocation main(DomElement main) {
		modify();
		this.main = main;
		return this;
	}
	private DomElement article;
	public DomElement article() {
		return article;
	}
	public SiteLocation article(DomElement article) {
		modify();
		this.article = article;
		return this;
	}
	private DomElement lead;
	public DomElement lead() {
		return lead;
	}
	public SiteLocation lead(DomElement lead) {
		modify();
		this.lead = lead;
		return this;
	}
	/*
	 * We will process location tree after it is produced by the site. By that time stack traces are lost.
	 * Not to mention that locations can be produced programmatically, in which case stack traces are not specific enough.
	 * We will therefore have to annotate all exceptions with an extra message that reveals location identity.
	 */
	private ExceptionFilter exceptions() {
		return new ExceptionFilter() {
			@Override
			public void handle(Throwable exception) {
				throw new RuntimeException("Exception in SiteLocation: " + SiteLocation.this, exception);
			}
		};
	}
	@Override
	public String toString() {
		/*
		 * Exceptions might be thrown from locations in various stages of incompleteness.
		 * We want to use as many identifying fields as possible.
		 */
		var suffix = site != null ? " @ " + site.uri().toString() : "";
		if (path != null)
			return path + suffix;
		if (subtree != null)
			return subtree + suffix;
		if (template != null)
			return template + suffix;
		if (clazz != null)
			return clazz.getName() + suffix;
		if (parent != null)
			return "child of " + parent;
		return super.toString();
	}
	/*
	 * We need to resolve two kinds of relative paths: resource paths and URL matching paths.
	 * Both of them are instances of decoded URL path. We will therefore use URI class to resolve them.
	 * We cannot use Path for resolving, because path separator varies with platform.
	 * We will use sibling-relative resolving, which means that in order to do child-relative resolving,
	 * base path must be normalized to end with a slash.
	 */
	private static String resolve(String base, String relative) {
		if (relative == null || relative.startsWith("/"))
			return relative;
		/*
		 * We cannot just use URI.create() as that one expects encoded URI while we need to process decoded paths.
		 */
		return Exceptions.wrap(IllegalArgumentException::new).get(() -> new URI(null, null, base, null).resolve(new URI(null, null, relative, null)).getPath());
	}
	/*
	 * This is called before load(), so it should resolve template location but nothing else.
	 */
	public void preresolve() {
		modify();
		if (site == null) {
			if (parent == null)
				throw new IllegalStateException("Root location must have non-null site reference.");
			site = parent.site;
		}
		String resourcesBase;
		if (clazz != null)
			resourcesBase = packagePath(clazz);
		else if (parent != null) {
			if (parent.resources == null)
				throw new IllegalStateException("Parent resource path must be defined.");
			resourcesBase = parent.resources;
		} else if (site != null)
			resourcesBase = packagePath(site.getClass());
		else
			throw new IllegalStateException("Site must be specified.");
		if (resources == null)
			resources = resourcesBase;
		else
			resources = resolve(resourcesBase, resources);
		if (!resources.endsWith("/"))
			resources += "/";
		template = resolve(resources, template);
		if (template != null && !template.endsWith(".xml"))
			template += ".xml";
	}
	/*
	 * Helper methods for parsing XML.
	 */
	private static final Pattern whitespaceRe = Pattern.compile("\\s+");
	private static String parseText(DomElement element) {
		return whitespaceRe.matcher(element.text()).replaceAll(" ").trim();
	}
	private static Optional<String> parseOptionalText(DomElement element) {
		var text = parseText(element);
		return text.isEmpty() ? Optional.empty() : Optional.of(text);
	}
	private static final DateTimeFormatter timeFormat = new DateTimeFormatterBuilder()
		.appendPattern("yyyy-MM-dd[ HH:mm[:ss]]")
		.parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
		.parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
		.parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
		.toFormatter(Locale.ROOT)
		.withZone(ZoneOffset.UTC);
	private static Instant parseDateTime(DomElement element) {
		return ZonedDateTime.parse(parseText(element), timeFormat).toInstant();
	}
	private static SiteLocation parseChild(DomElement element) {
		var path = parseText(element);
		var location = new SiteLocation()
			.template(path);
		int slash = path.lastIndexOf('/');
		if (slash >= 0) {
			location
				.resources(path.substring(0, slash))
				.template(path.substring(slash + 1));
		}
		location.key = location.template;
		return location;
	}
	private static OptionalDouble parseOptionalDouble(DomElement element) {
		var text = parseText(element);
		return text.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(Double.parseDouble(text));
	}
	@SuppressWarnings("unchecked")
	private Class<? extends SitePage> parseClass(DomElement element) {
		var text = parseText(element);
		var name = text.contains(".") ? text : resources.substring(1).replace('/', '.') + "." + text;
		var clazz = Exceptions.wrap().get(() -> Class.forName(name, false, site.getClass().getClassLoader()));
		if (!SitePage.class.isAssignableFrom(clazz))
			throw new IllegalStateException("Not a SitePage: " + clazz.getName());
		return (Class<? extends SitePage>)clazz;
	}
	private static byte[] read(String template) {
		SiteReload.watch();
		return Exceptions.sneak().get(() -> {
			try (InputStream stream = SiteLocation.class.getResourceAsStream(template)) {
				if (stream == null)
					throw new IllegalStateException("Cannot find template resource.");
				return IOUtils.toByteArray(stream);
			}
		});
	}
	/*
	 * Derived classes can override this method to add new element types in templates.
	 */
	protected void parse(DomElement element) {
		switch (element.tagname()) {
		case "child":
			add(parseChild(element));
			break;
		case "path":
			path = parseText(element);
			break;
		case "alias":
			alias(parseText(element));
			break;
		case "class":
			clazz = parseClass(element);
			break;
		case "priority":
			priority = parseOptionalDouble(element);
			break;
		case "language":
			language = parseOptionalText(element);
			break;
		case "title":
			title = parseText(element);
			break;
		case "supertitle":
			supertitle = parseOptionalText(element);
			break;
		case "extitle":
			extitle = parseText(element);
			break;
		case "breadcrumb":
			breadcrumb = parseText(element);
			break;
		case "description":
			description = parseText(element);
			break;
		case "published":
			published = parseDateTime(element);
			break;
		case "updated":
			updated = parseDateTime(element);
			break;
		case "body":
			body = element;
			break;
		case "main":
			main = element;
			break;
		case "article":
			article = element;
			break;
		case "lead":
			lead = element;
			break;
		default:
			throw new IllegalStateException("Unrecognized template element: " + element.tagname());
		}
	}
	private void parse(byte[] bytes) {
		/*
		 * Get rid of the unicode byte order mark at the beginning of the file
		 * in order to avoid "content is not allowed in prolog" exceptions from Java's XML parser.
		 */
		var text = new String(bytes, StandardCharsets.UTF_8).replace("\uFEFF", "");
		var parsed = Exceptions.sneak().get(() -> {
			var builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
			return DomElement.fromXml(builder.parse(new InputSource(new StringReader(text))).getDocumentElement());
		});
		if (!"template".equals(parsed.tagname()))
			throw new IllegalStateException("Unrecognized top element: " + parsed.tagname());
		for (DomElement element : parsed.elements().collect(toList()))
			parse(element);
	}
	private static class Cache {
		byte[] checksum;
		SiteLocation location = new SiteLocation();
		List<SiteLocation> children;
	}
	private static final Map<String, Cache> caches = new HashMap<>();
	private static synchronized Cache cache(String template) {
		SiteReload.watch();
		var bytes = read(template);
		var checksum = Exceptions.sneak().get(() -> MessageDigest.getInstance("SHA-256")).digest(bytes);
		var cache = caches.get(template);
		if (cache == null || !Arrays.equals(checksum, cache.checksum)) {
			cache = new Cache();
			cache.checksum = checksum;
			cache.location.parse(bytes);
			cache.children = cache.location.children;
			cache.location.children = new ArrayList<>();
			cache.location.freeze();
			for (var child : cache.children)
				child.freeze();
			caches.put(template, cache);
		}
		return cache;
	}
	/*
	 * This method by default loads the XML template, but derived classes may override it to load from other sources.
	 */
	@DraftCode("perhaps persistently cache parsed templates")
	public void load() {
		modify();
		if (template != null) {
			var cached = cache(template);
			merge(cached.location);
			/*
			 * Duplicate children defined in the template. We cannot reuse them in every tree we build.
			 */
			for (var child : cached.children) {
				add(new SiteLocation()
					.key(child.key)
					.resources(child.resources)
					.template(child.template));
			}
		}
	}
	private static final Pattern templateNameRe = Pattern.compile(".*/([^/.]+)(?:[.][^/]*)?");
	private static final Pattern resourceNameRe = Pattern.compile(".*/([^/]+)");
	/*
	 * This runs after template is loaded. It fills in defaults, resolves relative paths, etc.
	 * Derived class can override it to resolve its own fields.
	 */
	public void resolve() {
		modify();
		Objects.requireNonNull(site, "Site reference must be non-null.");
		asset = resolve(resources, asset);
		if (path == null && subtree == null) {
			if (parent == null)
				path = "/";
			else if (template != null) {
				Matcher matcher = templateNameRe.matcher(template);
				if (matcher.matches())
					path = matcher.group(1);
			} else if (asset != null) {
				Matcher matcher = resourceNameRe.matcher(asset);
				if (matcher.matches())
					path = matcher.group(1);
			} else if (virtual)
				path = parent.path;
		}
		if (path != null && !path.startsWith("/")) {
			if (parent == null)
				throw new IllegalStateException("Relative path cannot be used on root location.");
			if (parent.path == null)
				throw new IllegalStateException("Relative path can be only used if the parent has a path.");
			path = resolve(parent.path, path);
		}
		aliases.replaceAll(a -> {
			if (path == null)
				throw new IllegalStateException("Alias can be only defined for location that has a path.");
			return resolve(path, a);
		});
		if (viewer == null)
			viewer = parent != null ? parent.viewer : site::viewer;
		if (priority == null) {
			if (parent == null)
				priority(1);
			else if (parent.priority.isPresent())
				priority(Math.max(0, Precision.round(parent.priority.getAsDouble() - 0.1, 3)));
			else
				priority = OptionalDouble.empty();
		}
		if (language == null)
			language = parent != null ? parent.language : Optional.of(site.language());
		if (supertitle == null)
			supertitle = parent != null ? parent.supertitle : Optional.of(site.title());
		if (breadcrumb == null)
			breadcrumb = title;
	}
	public void freeze() {
		/*
		 * Do not allow lists to be wrapped twice.
		 */
		if (!frozen) {
			frozen = true;
			/*
			 * Transform all fields, so that we can safely return them without additional wrapping.
			 */
			children = Collections.unmodifiableList(children);
			aliases = Collections.unmodifiableList(aliases);
			if (body != null)
				body.freeze();
			if (main != null)
				main.freeze();
			if (article != null)
				article.freeze();
			if (lead != null)
				lead.freeze();
		}
	}
	/*
	 * Derived class can override this method to add more validations for the completed location.
	 */
	public void validate() {
		Objects.requireNonNull(site, "Site reference must be non-null.");
		Objects.requireNonNull(resources, "Resource path must be defined.");
		if (!resources.startsWith("/") || !resources.endsWith("/"))
			throw new IllegalArgumentException("Resource path must start and end with '/'.");
		if (template != null && !template.startsWith("/"))
			throw new IllegalArgumentException("Template path must be absolute.");
		if (asset != null && !asset.startsWith("/"))
			throw new IllegalArgumentException("Static asset path must be absolute.");
		int matchers = 0;
		if (path != null) {
			++matchers;
			if (!path.startsWith("/"))
				throw new IllegalArgumentException("Matched URL path must be absolute.");
		}
		if (subtree != null)
			++matchers;
		if (matchers > 1)
			throw new IllegalStateException("Multiple URL matchers defined.");
		if (!virtual && matchers == 0)
			throw new IllegalStateException("Non-virtual location must have URL matcher defined.");
		if (virtual && matchers > 0 && path == null)
			throw new IllegalStateException("Virtual location can have only exact path URL matcher.");
		if (path == null && !aliases.isEmpty())
			throw new IllegalStateException("Aliases can be defined only when matching exact URL path.");
		for (var alias : aliases)
			if (!alias.startsWith("/"))
				throw new IllegalArgumentException("Alias path must be absolute.");
		Objects.requireNonNull(viewer, "Fallback viewer constructor must be non-null.");
		int handlers = 0;
		if (clazz != null)
			++handlers;
		if (constructor != null)
			++handlers;
		if (redirect != null)
			++handlers;
		if (servlet != null)
			++handlers;
		if (asset != null)
			++handlers;
		if (handlers > 1)
			throw new IllegalStateException("Location cannot have multiple request handlers.");
		if (virtual && handlers > 0)
			throw new IllegalStateException("Virtual location cannot have request handler defined.");
		if (redirect != null && !Arrays.stream(REDIRECT_CODES).anyMatch(s -> s == status))
			throw new IllegalStateException("Invalid status code for redirect.");
		Objects.requireNonNull(priority, "Priority must be non-null.");
		Objects.requireNonNull(language, "Language must be non-null.");
		Objects.requireNonNull(supertitle, "Supertitle must be non-null.");
	}
	@Override
	public SiteLocation clone() {
		var clone = new SiteLocation();
		clone.site = site;
		clone.parent = parent;
		clone.children = new ArrayList<>(children);
		clone.base = this;
		clone.virtual = virtual;
		clone.path = path;
		clone.aliases = new ArrayList<>(aliases);
		clone.subtree = subtree;
		clone.clazz = clazz;
		clone.constructor = constructor;
		clone.viewer = viewer;
		clone.resources = resources;
		clone.template = template;
		clone.asset = asset;
		clone.status = status;
		clone.redirect = redirect;
		clone.servlet = servlet;
		clone.priority = priority;
		clone.language = language;
		clone.title = title;
		clone.supertitle = supertitle;
		clone.extitle = extitle;
		clone.breadcrumb = breadcrumb;
		clone.description = description;
		clone.published = published;
		clone.updated = updated;
		/*
		 * Do not make fragments mutable. Only make the whole location mutable.
		 * Caller can make fragments mutable by cloning them explicitly.
		 */
		clone.body = body;
		clone.main = main;
		clone.article = article;
		clone.lead = lead;
		return clone;
	}
	private static <T> T merge(T base, T other) {
		return other != null ? other : base;
	}
	private static List<SiteLocation> mergeChildren(List<SiteLocation> bases, List<SiteLocation> others) {
		int last = -1;
		var keys = new Object2IntOpenHashMap<String>();
		var results = new ArrayList<SiteLocation>();
		for (int i = 0; i < others.size(); ++i) {
			var other = others.get(i);
			if (other.key != null)
				keys.put(other.key, i);
		}
		for (int i = 0; i < bases.size(); ++i) {
			var base = bases.get(i);
			if (base.key == null || !keys.containsKey(base.key))
				results.add(base);
			else {
				int at = keys.getInt(base.key);
				if (at <= last)
					throw new IllegalStateException("Incompatible ordering of merged child lists.");
				for (int j = last + 1; j < at; ++j)
					results.add(others.get(j));
				base.merge(others.get(at));
				results.add(base);
				last = at;
			}
		}
		for (int i = last + 1; i < others.size(); ++i)
			results.add(others.get(i));
		return results;
	}
	public void merge(SiteLocation other) {
		modify();
		site = merge(site, other.site);
		parent = merge(parent, other.parent);
		children = mergeChildren(children, other.children);
		base = merge(base, other.base);
		virtual = other.virtual;
		path = merge(path, other.path);
		for (var alias : other.aliases)
			if (!aliases.contains(alias))
				aliases.add(alias);
		subtree = merge(subtree, other.subtree);
		clazz = merge(clazz, other.clazz);
		constructor = merge(constructor, other.constructor);
		viewer = merge(viewer, other.viewer);
		resources = merge(resources, other.resources);
		template = merge(template, other.template);
		asset = merge(asset, other.asset);
		if (other.status > 0)
			status = other.status;
		redirect = merge(redirect, other.redirect);
		servlet = merge(servlet, other.servlet);
		if (!other.priority.isEmpty())
			priority = other.priority;
		language = merge(language, other.language);
		title = merge(title, other.title);
		supertitle = merge(supertitle, other.supertitle);
		extitle = merge(extitle, other.extitle);
		breadcrumb = merge(breadcrumb, other.breadcrumb);
		description = merge(description, other.description);
		published = merge(published, other.published);
		updated = merge(updated, other.updated);
		body = merge(body, other.body);
		main = merge(main, other.main);
		article = merge(article, other.article);
		lead = merge(lead, other.lead);
	}
	public void compile() {
		exceptions().run(() -> {
			preresolve();
			load();
			resolve();
			site.intercept(this);
			freeze();
			validate();
		});
	}
}
