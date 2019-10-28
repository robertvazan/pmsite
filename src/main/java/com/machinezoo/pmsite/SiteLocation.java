// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

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
		if (child != null)
			children.add(child);
		return this;
	}
	public Stream<SiteLocation> flatten() {
		return Stream.concat(Stream.of(this), children().stream().flatMap(SiteLocation::flatten));
	}
	/*
	 * Location can have one primary URL where it is served and a number of aliases that 301 to the primary location.
	 * If path is null, the location is not mapped, but it can still serve as a virtual parent for other locations.
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
		return page;
	}
	public SiteLocation page(Supplier<SitePage> page) {
		Objects.requireNonNull(page);
		this.page = () -> page.get().location(this);
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
		this.priority = priority;
		return this;
	}
	public SiteLocation priority(double priority) {
		if (priority < 0 || priority > 1)
			throw new IllegalArgumentException();
		return priority(OptionalDouble.of(priority));
	}
	/*
	 * In order to support inheritance of location properties,
	 * we will call configure() on every newly constructed location tree.
	 * Configuration is smart, i.e. it's not just simple chaining to ancestors.
	 * Every location also gets a SiteConfiguration reference.
	 */
	public void configure(SiteConfiguration site) {
		this.site = site;
		for (SiteLocation child : children)
			child.configure(this);
	}
	private SiteConfiguration site;
	public SiteConfiguration site() {
		return site;
	}
	public void configure(SiteLocation parent) {
		if (site == null)
			site = parent.site;
		if (page == null)
			page = parent.page;
		if (!priority.isPresent())
			priority = parent.priority;
		for (SiteLocation child : children)
			child.configure(this);
	}
}
