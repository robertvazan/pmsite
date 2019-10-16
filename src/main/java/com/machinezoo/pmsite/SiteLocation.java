package com.machinezoo.pmsite;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/*
 * Static equivalent of SitePage. While SitePage is instantiated for every page view,
 * SiteLocation is defined only once per URL.
 */
public class SiteLocation {
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
	private Supplier<SitePage> page;
	public Supplier<SitePage> page() {
		return page;
	}
	public SiteLocation page(Supplier<SitePage> page) {
		Objects.requireNonNull(page);
		this.page = page;
		return this;
	}
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
}
