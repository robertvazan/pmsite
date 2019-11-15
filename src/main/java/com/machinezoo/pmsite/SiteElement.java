// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import static java.util.stream.Collectors.*;
import java.util.*;
import java.util.function.*;
import com.machinezoo.noexception.*;
import com.machinezoo.pushmode.dom.*;

/*
 * Custom element for use in SiteTemplate.
 * It is recreated every time it is expanded in the template.
 */
public abstract class SiteElement {
	/*
	 * Default element name simplifies configuration and standardizes content of XML templates.
	 */
	public abstract String name();
	/*
	 * Provide access to SiteTemplate, so that custom elements can include page metadata in generated HTML.
	 */
	private SiteTemplate template;
	public SiteElement template(SiteTemplate template) {
		this.template = template;
		return this;
	}
	public SiteTemplate template() {
		return template;
	}
	/*
	 * Provide access to the page, so that custom elements can include metadata from the page or its SiteLocation.
	 */
	private SitePage page;
	public SiteElement page(SitePage page) {
		this.page = page;
		return this;
	}
	public SitePage page() {
		return page;
	}
	/*
	 * Element as it appears in template XML.
	 */
	private DomElement source;
	public SiteElement source(DomElement source) {
		this.source = source;
		return this;
	}
	public DomElement source() {
		return source;
	}
	/*
	 * This is what SiteTemplate calls. Custom elements generally shouldn't override it
	 * as it provides attribute annotation by default.
	 */
	public DomContent render() {
		DomContent rendered = expand();
		if (rendered instanceof DomElement)
			rendered = annotate((DomElement)rendered);
		return rendered;
	}
	/*
	 * This is what custom elements should usually override to provide their generated content.
	 * This method is abstract to make it easier to implement the class.
	 * In the rare cases when custom elements override render() directly, they can just return null here.
	 */
	public abstract DomContent expand();
	/*
	 * Here the custom element can configure attributes it consumes.
	 * These attributes will not be appended to the output element below.
	 */
	public boolean consumes(String name) {
		return false;
	}
	/*
	 * We want to allow extra attributes on custom elements, especially class for styling.
	 * This method adds attributes declared on source element to the generated element.
	 */
	public DomElement annotate(DomElement target) {
		List<DomAttribute> attributes = source.attributes().stream()
			.filter(a -> !consumes(a.name()))
			.collect(toList());
		/*
		 * We cannot edit the rendered element directly, because it might be shared or frozen.
		 * Perform a fast shallow copy, because we only need to change the top-level element.
		 */
		DomElement annotated = new DomElement(target.tagname())
			.key(target.key())
			.id(target.id())
			.set(target.attributes())
			/*
			 * This is the only change we are making.
			 * Attributes explicitly set on the reference element override generated attributes.
			 */
			.set(attributes)
			.add(target.children());
		for (DomListener listener : target.listeners())
			annotated.subscribe(listener);
		return annotated;
	}
	/*
	 * Simple implementation for custom element are below.
	 * Block variants provide automatic exception handling. Inline variants do not.
	 * Supplier variants are for content that doesn't need information from the template.
	 * Function variants can access many of the features of SiteElement.
	 */
	public static Supplier<SiteElement> inline(String name, Supplier<? extends DomContent> supplier) {
		return () -> new SiteElement() {
			@Override public String name() {
				return name;
			}
			@Override public DomContent expand() {
				return supplier.get();
			}
		};
	}
	public static Supplier<SiteElement> inline(String name, Function<SiteElement, ? extends DomContent> function) {
		return () -> new SiteElement() {
			@Override public String name() {
				return name;
			}
			@Override public DomContent expand() {
				return function.apply(this);
			}
			@Override public boolean consumes(String name) {
				return true;
			}
		};
	}
	public static Supplier<SiteElement> block(String name, Supplier<? extends DomContent> supplier) {
		return () -> new SiteElement() {
			@Override public String name() {
				return name;
			}
			@Override public DomContent expand() {
				try {
					return supplier.get();
				} catch (Throwable ex) {
					Exceptions.log().handle(ex);
					return SitePage.formatError(ex);
				}
			}
		};
	}
	public static Supplier<SiteElement> block(String name, Function<SiteElement, ? extends DomContent> function) {
		return () -> new SiteElement() {
			@Override public String name() {
				return name;
			}
			@Override public DomContent expand() {
				try {
					return function.apply(this);
				} catch (Throwable ex) {
					Exceptions.log().handle(ex);
					return SitePage.formatError(ex);
				}
			}
			@Override public boolean consumes(String name) {
				return true;
			}
		};
	}
}
