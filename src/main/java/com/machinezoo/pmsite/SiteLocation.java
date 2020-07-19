// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite;

import static java.util.stream.Collectors.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.regex.*;
import java.util.stream.*;
import org.apache.commons.math3.util.*;
import com.machinezoo.hookless.servlets.*;
import com.machinezoo.noexception.*;
import com.machinezoo.pushmode.dom.*;
import com.machinezoo.stagean.*;

/**
 * Static equivalent of {@link SitePage}.
 * While {@link SitePage} is instantiated for every page view, {@code SiteLocation} is defined only once per URL.
 */
@StubDocs
@DraftApi
public class SiteLocation {
	/*
	 * Locations form a tree, in which parents can provide defaults and context for child locations.
	 */
	private List<SiteLocation> children = new ArrayList<>();
	public List<SiteLocation> children() {
		return children;
	}
	public SiteLocation add(SiteLocation child) {
		/*
		 * Tolerate null, so that we can make constructs like add(condition ? null : new SiteLocation()...).
		 */
		if (child != null)
			children.add(child);
		return this;
	}
	public Stream<SiteLocation> flatten() {
		return Stream.concat(Stream.of(this), children().stream().flatMap(SiteLocation::flatten));
	}
	/*
	 * Virtual locations are not mapped. They serve as a parent for other locations, providing defaults and context.
	 */
	private boolean virtual;
	public boolean virtual() {
		return virtual;
	}
	public SiteLocation virtual(boolean virtual) {
		this.virtual = virtual;
		return this;
	}
	/*
	 * Non-virtual location has one primary URL where it is served and a number of aliases that 301 to the primary location.
	 * Primary URL may be relative to ancestor URL. Aliases may be relative to the primary URL.
	 */
	private String path;
	public String path() {
		return path;
	}
	public SiteLocation path(String path) {
		this.path = path;
		return this;
	}
	private List<String> aliases = new ArrayList<>();
	public List<String> aliases() {
		return aliases;
	}
	public SiteLocation alias(String alias) {
		Objects.requireNonNull(alias);
		aliases.add(alias);
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
		if (subtree != null && (!subtree.startsWith("/") || !subtree.endsWith("/")))
			throw new IllegalStateException("Subtree path must start and end with '/': " + subtree);
		this.subtree = subtree;
		return this;
	}
	/*
	 * Location can be mapped to a number of possible objects:
	 * - SitePage constructor
	 * - template
	 * - static resource
	 * - 301 redirect
	 * - 410 gone
	 * - rewrite URL and 301 to it
	 * - reactive servlet
	 * - static content from resource
	 * - maybe other
	 */
	private Supplier<SitePage> page;
	public Supplier<SitePage> page() {
		return page;
	}
	public SiteLocation page(Supplier<SitePage> page) {
		this.page = page;
		return this;
	}
	private String redirect;
	public String redirect() {
		return redirect;
	}
	public SiteLocation redirect(String redirect) {
		this.redirect = redirect;
		return this;
	}
	private boolean gone;
	public boolean gone() {
		return gone;
	}
	public SiteLocation gone(boolean gone) {
		this.gone = gone;
		return this;
	}
	private Function<String, String> rewrite;
	public Function<String, String> rewrite() {
		return rewrite;
	}
	public SiteLocation rewrite(Function<String, String> rewrite) {
		this.rewrite = rewrite;
		return this;
	}
	private ReactiveServlet servlet;
	public ReactiveServlet servlet() {
		return servlet;
	}
	public SiteLocation servlet(ReactiveServlet servlet) {
		this.servlet = servlet;
		return this;
	}
	private String resource;
	public String resource() {
		return resource;
	}
	public SiteLocation resource(String resource) {
		this.resource = resource;
		return this;
	}
	public SiteLocation resource(Class<?> owner, String resource) {
		return resource(!resource.startsWith("/") ? resourceDirectory(owner) + resource : resource);
	}
	/*
	 * Template paths (and possibly other resource paths) may be relative to ancestor template or site configuration class.
	 * Resource directory can be also specified indirectly via class reference,
	 * but this is discouraged, because it may have undesirable effect on app launch performance.
	 * Templates can have a fallback page that is used if no location-specific page is provided.
	 */
	private String template;
	public String template() {
		return template;
	}
	public SiteLocation template(String template) {
		this.template = template;
		return this;
	}
	public SiteLocation template(Class<?> owner, String template) {
		return template(template != null && !template.startsWith("/") ? resourceDirectory(owner) + template : template);
	}
	private static String resourceDirectory(Class<?> clazz) {
		return "/" + clazz.getPackage().getName().replace('.', '/') + "/";
	}
	private Supplier<SitePage> viewer;
	public Supplier<SitePage> viewer() {
		return viewer;
	}
	public SiteLocation viewer(Supplier<SitePage> viewer) {
		this.viewer = viewer;
		return this;
	}
	/*
	 * Priority can be configured for XML sitemaps.
	 */
	private OptionalDouble priority = OptionalDouble.empty();
	public OptionalDouble priority() {
		return priority;
	}
	public SiteLocation priority(OptionalDouble priority) {
		Objects.requireNonNull(priority);
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
	 * Some template properties are pulled into location objects in order to allow building page lists.
	 */
	private String title;
	public String title() {
		return title;
	}
	public SiteLocation title(String title) {
		this.title = title;
		return this;
	}
	/*
	 * Supertitle is usually identical to site title, but it can be modified for subtrees of locations.
	 */
	private String supertitle;
	public String supertitle() {
		return supertitle;
	}
	public SiteLocation supertitle(String supertitle) {
		this.supertitle = supertitle;
		return this;
	}
	/*
	 * Breadcrumb names are essentially short titles.
	 * Besides serving as breadcrumbs, they can be used to build hierarchical menus.
	 */
	private String breadcrumb;
	public String breadcrumb() {
		return breadcrumb;
	}
	public SiteLocation breadcrumb(String breadcrumb) {
		this.breadcrumb = breadcrumb;
		return this;
	}
	private Instant published;
	public Instant published() {
		return published;
	}
	public SiteLocation published(Instant published) {
		this.published = published;
		return this;
	}
	private DomContent lead;
	public DomContent lead() {
		return lead;
	}
	public SiteLocation lead(DomContent lead) {
		this.lead = lead;
		return this;
	}
	/*
	 * In order to support inheritance of location properties,
	 * we will call configure() on every newly constructed location tree.
	 * Configuration is smart, i.e. it's not just simple chaining to ancestors.
	 * Every location also gets a SiteConfiguration reference.
	 */
	public void compile(SiteConfiguration site) {
		Objects.requireNonNull(site);
		this.site = site;
		compile();
	}
	private SiteConfiguration site;
	public SiteConfiguration site() {
		return site;
	}
	private SiteLocation parent;
	public SiteLocation parent() {
		return parent;
	}
	public List<SiteLocation> ancestors() {
		return parent != null ? parent.ancestorsAndSelf() : Collections.emptyList();
	}
	public List<SiteLocation> ancestorsAndSelf() {
		List<SiteLocation> ancestors = new ArrayList<>();
		for (SiteLocation ancestor = this; ancestor != null; ancestor = ancestor.parent())
			ancestors.add(ancestor);
		Collections.reverse(ancestors);
		return ancestors;
	}
	private void compile(SiteLocation parent) {
		site = parent.site;
		this.parent = parent;
		compile();
	}
	private static final Pattern templateNameRe = Pattern.compile(".*/([a-zA-Z0-9_-]+)(?:[.][a-z]+)*");
	private void compile() {
		template = inherit(template, l -> l.template, () -> resourceDirectory(site.getClass()));
		if (template != null) {
			try {
				SiteTemplate xml = SiteTemplate.resource(SiteLocation.class, template)
					.metadataOnly(true)
					.load();
				if (path == null)
					path = xml.path();
				if (path == null && subtree == null && parent != null) {
					Matcher matcher = templateNameRe.matcher(template);
					if (matcher.matches())
						path = matcher.group(1);
				}
				aliases.addAll(xml.aliases());
				if (title == null)
					title = xml.title();
				if (supertitle == null)
					supertitle = xml.supertitle();
				if (breadcrumb == null)
					breadcrumb = xml.breadcrumb();
				if (published == null)
					published = xml.published();
				if (lead == null)
					lead = xml.lead();
			} catch (Throwable ex) {
				throw new IllegalStateException("Failed to read metadata from template: " + this, ex);
			}
		}
		if (path == null && subtree == null && parent == null)
			path = "/";
		if (subtree == null)
			path = inherit(path, l -> l.path, () -> "/");
		if (path != null && subtree != null)
			throw new IllegalStateException("Location cannot be mapped to both path and subtree: " + this);
		if (!virtual && path == null && subtree == null)
			throw new IllegalStateException("Non-virtual location must be mapped to at least one path: " + this);
		if (virtual && !aliases.isEmpty())
			throw new IllegalStateException("Virtual location cannot have aliases: " + this);
		if (subtree != null && !aliases.isEmpty())
			throw new IllegalStateException("Subtree mapping cannot have aliases: " + this);
		aliases = aliases.stream().map(a -> resolve(path, a)).collect(toList());
		if (supertitle == null)
			supertitle = parent != null ? parent.supertitle : site.title();
		if (supertitle != null && supertitle.isEmpty())
			supertitle = null;
		if (breadcrumb == null)
			breadcrumb = title;
		if (viewer == null)
			viewer = parent != null ? parent.viewer : site::viewer;
		if (page == null && template != null)
			page = viewer;
		resource = inherit(resource, l -> l.template, () -> resourceDirectory(site.getClass()));
		if (resource != null && path == null)
			throw new IllegalStateException("Resource must be exposed on a concrete path: " + this);
		int mappings = 0;
		if (page != null)
			++mappings;
		if (redirect != null)
			++mappings;
		if (gone)
			++mappings;
		if (rewrite != null)
			++mappings;
		if (servlet != null)
			++mappings;
		if (resource != null)
			++mappings;
		if (!virtual && mappings == 0)
			throw new IllegalStateException("Location must be mapped to something: " + this);
		if (virtual && mappings > 0)
			throw new IllegalStateException("Virtual location should not be mapped to anything: " + this);
		if (mappings > 1)
			throw new IllegalStateException("Ambiguous multiple mappings: " + this);
		if (!priority.isPresent()) {
			if (parent == null)
				priority(1);
			else if (parent.priority.isPresent())
				priority(Math.max(0, Precision.round(parent.priority.getAsDouble() - 0.1, 3)));
		}
		for (SiteLocation child : children)
			child.compile(this);
	}
	private String inherit(String relative, Function<SiteLocation, String> getter, Supplier<String> fallback) {
		if (relative == null || relative.startsWith("/"))
			return relative;
		SiteLocation ancestor = parent;
		while (ancestor != null && getter.apply(ancestor) == null)
			ancestor = ancestor.parent;
		return resolve(ancestor != null ? getter.apply(ancestor) : fallback.get(), relative);
	}
	private static String resolve(String absolute, String relative) {
		if (relative == null || relative.startsWith("/"))
			return relative;
		return Exceptions.sneak().get(() -> new URI(absolute)).resolve(relative).toString();
	}
	@Override
	public String toString() {
		if (site != null && (path != null || subtree != null)) {
			URI uri = site.uri();
			if (uri != null && path != null)
				return uri.resolve(path).toString();
			if (uri != null && subtree != null)
				return uri.resolve(subtree).toString();
		}
		if (path != null)
			return path;
		if (subtree != null)
			return subtree;
		if (template != null)
			return template;
		if (parent != null)
			return "child of " + parent;
		return super.toString();
	}
}
