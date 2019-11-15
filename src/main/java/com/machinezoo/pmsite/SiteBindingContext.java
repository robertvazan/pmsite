package com.machinezoo.pmsite;

import com.machinezoo.pushmode.dom.*;

/*
 * Binding parameters are kept in a separate class, so that new parameters can be added if necessary.
 */
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
}
