// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import java.net.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

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
	 */
	private String path;
	public String path() {
		return path;
	}
	public SiteLocation path(String path) {
		Objects.requireNonNull(path);
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
	 * - static resource
	 * - external 301 redirect
	 * - maybe other
	 */
	private Supplier<SitePage> page;
	public Supplier<SitePage> page() {
		if (page == null)
			return null;
		return () -> page.get().location(this);
	}
	public SiteLocation page(Supplier<SitePage> page) {
		Objects.requireNonNull(page);
		this.page = page;
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
		if (parent != null && path == null && (virtual || parent.virtual))
			path = parent.path;
		else if (parent != null && path != null && !path.startsWith("/") && parent.path != null)
			path = parent.path.endsWith("/") ? parent.path + path : parent.path + "/" + path;
		if (!virtual && path == null)
			throw new IllegalStateException("Non-virtual location must have a path: " + this);
		if (parent != null && page == null)
			page = parent.page;
		if (!virtual && page == null)
			throw new IllegalStateException("Location must be mapped to something: " + this);
		if (parent != null && !priority.isPresent())
			priority = parent.priority;
		for (SiteLocation child : children)
			child.configure(this);
	}
	@Override public String toString() {
		if (site != null && path != null) {
			URI uri = site.uri();
			if (uri != null)
				return uri.resolve(path).toString();
		}
		if (path != null)
			return path;
		if (parent != null)
			return "child of " + parent;
		return super.toString();
	}
}
