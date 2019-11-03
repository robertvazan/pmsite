// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

/*
 * Icons currently have to be generated semi-automatically via https://realfavicongenerator.net/
 * Later, we could generate them on the fly from one master image.
 */
public class SiteIcon {
	private String png180;
	public String png180() {
		return png180;
	}
	public SiteIcon png180(String png180) {
		this.png180 = png180;
		return this;
	}
	private String png32;
	public String png32() {
		return png32;
	}
	public SiteIcon png32(String png32) {
		this.png32 = png32;
		return this;
	}
	private String png16;
	public String png16() {
		return png16;
	}
	public SiteIcon png16(String png16) {
		this.png16 = png16;
		return this;
	}
	private String manifest;
	public String manifest() {
		return manifest;
	}
	public SiteIcon manifest(String manifest) {
		this.manifest = manifest;
		return this;
	}
}
