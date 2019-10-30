// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import static java.util.stream.Collectors.*;
import java.net.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;
import com.machinezoo.noexception.*;

/*
 * Static equivalent of SitePage. While SitePage is instantiated for every page view,
 * SiteLocation is defined only once per URL.
 */
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
	 * Location can be mapped to a number of possible objects:
	 * - SitePage constructor
	 * - template
	 * - static resource
	 * - external 301 redirect
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
	/*
	 * Template paths (and possibly other resource paths) may be relative to ancestor template or site configuration class.
	 * Resource directory can be also specified indirectly via class reference,
	 * but this is discouraged, because it may have undesirable effect of app launch performance.
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
	private Supplier<SitePage> templatePage;
	public Supplier<SitePage> templatePage() {
		return templatePage;
	}
	public SiteLocation templatePage(Supplier<SitePage> page) {
		templatePage = page;
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
	 * In order to support inheritance of location properties,
	 * we will call configure() on every newly constructed location tree.
	 * Configuration is smart, i.e. it's not just simple chaining to ancestors.
	 * Every location also gets a SiteConfiguration reference.
	 */
	public void configure(SiteConfiguration site) {
		Objects.requireNonNull(site);
		this.site = site;
		if (templatePage == null)
			templatePage = site::templatePage;
		configure();
	}
	private SiteConfiguration site;
	public SiteConfiguration site() {
		return site;
	}
	private SiteLocation parent;
	public SiteLocation parent() {
		return parent;
	}
	private void configure(SiteLocation parent) {
		site = parent.site;
		this.parent = parent;
		configure();
	}
	private void configure() {
		template = inherit(template, l -> l.template, () -> resourceDirectory(site.getClass()));
		path = inherit(path, l -> l.path, () -> "/");
		if (!virtual && path == null)
			throw new IllegalStateException("Non-virtual location must have a path: " + this);
		if (virtual && !aliases.isEmpty())
			throw new IllegalStateException("Virtual location cannot have aliases: " + this);
		aliases = aliases.stream().map(a -> resolve(path, a)).collect(toList());
		if (parent != null && templatePage == null)
			templatePage = parent.templatePage;
		if (page == null && template != null)
			page = templatePage;
		if (!virtual && page == null)
			throw new IllegalStateException("Location must be mapped to something: " + this);
		if (parent != null && !priority.isPresent())
			priority = parent.priority;
		for (SiteLocation child : children)
			child.configure(this);
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
	@Override public String toString() {
		if (site != null && path != null) {
			URI uri = site.uri();
			if (uri != null)
				return uri.resolve(path).toString();
		}
		if (path != null)
			return path;
		if (template != null)
			return template;
		if (parent != null)
			return "child of " + parent;
		return super.toString();
	}
}
