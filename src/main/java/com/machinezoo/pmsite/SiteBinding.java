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
	 * Bindings override this method to provide their generated content.
	 */
	public abstract DomContent expand(SiteBindingContext context);
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
	 * None of these implementations consume any attributes. That requires derived class.
	 */
	public static SiteBinding inline(String name, Supplier<? extends DomContent> supplier) {
		return new SiteBinding() {
			@Override public String name() {
				return name;
			}
			@Override public DomContent expand(SiteBindingContext context) {
				return supplier.get();
			}
		};
	}
	public static SiteBinding inline(String name, Function<SiteBindingContext, ? extends DomContent> function) {
		return new SiteBinding() {
			@Override public String name() {
				return name;
			}
			@Override public DomContent expand(SiteBindingContext context) {
				return function.apply(context);
			}
		};
	}
	public static SiteBinding block(String name, Supplier<? extends DomContent> supplier) {
		return new SiteBinding() {
			@Override public String name() {
				return name;
			}
			@Override public DomContent expand(SiteBindingContext context) {
				try {
					return supplier.get();
				} catch (Throwable ex) {
					Exceptions.log().handle(ex);
					return SitePage.formatError(ex);
				}
			}
		};
	}
	public static SiteBinding block(String name, Function<SiteBindingContext, ? extends DomContent> function) {
		return new SiteBinding() {
			@Override public String name() {
				return name;
			}
			@Override public DomContent expand(SiteBindingContext context) {
				try {
					return function.apply(context);
				} catch (Throwable ex) {
					Exceptions.log().handle(ex);
					return SitePage.formatError(ex);
				}
			}
		};
	}
}
