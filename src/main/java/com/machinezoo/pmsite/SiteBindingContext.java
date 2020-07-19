// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite;

import java.util.*;
import com.machinezoo.pushmode.dom.*;
import com.machinezoo.stagean.*;

/*
 * Binding parameters are kept in a separate class, so that new parameters can be added if necessary.
 */
/**
 * Information exchange point between {@link SiteBinding} and {@link SiteTemplate}.
 */
@StubDocs
@DraftApi
public abstract class SiteBindingContext {
	/*
	 * Source element as it appears in template XML.
	 * Only source element is really necessary for bindings to work, so only that one is abstract.
	 */
	public abstract DomElement source();
	/*
	 * If possible, provide access to SiteTemplate, so that bindings can use page metadata in generated HTML.
	 */
	public SiteTemplate template() {
		return null;
	}
	/*
	 * If possible, provide access to the page, so that bindings can use metadata from the page or its SiteLocation.
	 */
	public SitePage page() {
		return null;
	}
	/*
	 * Consumed attributes will not be copied from source element to the generated element.
	 * This is useful for passing parameters directly to the binding
	 * while still allowing extra attributes (especially class) on the custom elements.
	 * 
	 * List of consumed attributes can be dynamic, i.e. it may depend on the code path taken in the binding.
	 * Bindings are expected to call consume() or to directly manipulate the set returned by consumed()
	 * in order to prevent consumed attributes from appearing in output HTML.
	 */
	private Set<String> consumed = new HashSet<>();
	public Set<String> consumed() {
		return consumed;
	}
	public String consume(String name) {
		consumed.add(name);
		return source().attributeAsString(name);
	}
	public void consumeAll() {
		source().attributes().stream().forEach(a -> consume(a.name()));
	}
}
