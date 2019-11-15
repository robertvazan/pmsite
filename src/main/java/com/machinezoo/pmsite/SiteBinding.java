// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import java.util.function.*;
import com.machinezoo.noexception.*;
import com.machinezoo.pushmode.dom.*;

/*
 * SiteTemplate supports bindings that expand special x-* XML elements in template XML.
 * SiteBinding defines properties and behavior of these custom elements.
 */
public abstract class SiteBinding {
	/*
	 * Default binding name simplifies configuration and standardizes content of XML templates.
	 */
	public abstract String name();
	/*
	 * Provide access to SiteTemplate, so that bindings can use page metadata in generated HTML.
	 */
	private SiteTemplate template;
	public SiteBinding template(SiteTemplate template) {
		this.template = template;
		return this;
	}
	public SiteTemplate template() {
		return template;
	}
	/*
	 * Provide access to the page, so that bindings can use metadata from the page or its SiteLocation.
	 */
	private SitePage page;
	public SiteBinding page(SitePage page) {
		this.page = page;
		return this;
	}
	public SitePage page() {
		return page;
	}
	/*
	 * Source element as it appears in template XML.
	 */
	private DomElement source;
	public SiteBinding source(DomElement source) {
		this.source = source;
		return this;
	}
	public DomElement source() {
		return source;
	}
	/*
	 * Bindings override this method to provide their generated content.
	 */
	public abstract DomContent expand();
	/*
	 * Here the binding can configure source element attributes it consumes.
	 * These attributes will not be copied from source element to the generated element.
	 */
	public boolean consumes(String name) {
		return false;
	}
	/*
	 * Simple implementations of bindings are below.
	 * Block variants provide automatic exception handling. Inline variants do not.
	 * Supplier variants are for content that doesn't need information from the template.
	 * Function variants can access many of the features of SiteBinding.
	 */
	public static Supplier<SiteBinding> inline(String name, Supplier<? extends DomContent> supplier) {
		return () -> new SiteBinding() {
			@Override public String name() {
				return name;
			}
			@Override public DomContent expand() {
				return supplier.get();
			}
		};
	}
	public static Supplier<SiteBinding> inline(String name, Function<SiteBinding, ? extends DomContent> function) {
		return () -> new SiteBinding() {
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
	public static Supplier<SiteBinding> block(String name, Supplier<? extends DomContent> supplier) {
		return () -> new SiteBinding() {
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
	public static Supplier<SiteBinding> block(String name, Function<SiteBinding, ? extends DomContent> function) {
		return () -> new SiteBinding() {
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
