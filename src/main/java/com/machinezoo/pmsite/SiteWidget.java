// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite;

import java.lang.annotation.*;
import com.machinezoo.stagean.*;

/**
 * Annotates widget method to be used with {@link SiteTemplate}.
 */
@StubDocs
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SiteWidget {
	String name() default "";
	String value() default "";
}
