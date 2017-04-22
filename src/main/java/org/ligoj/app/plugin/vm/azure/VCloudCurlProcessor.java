package org.ligoj.app.plugin.vm.azure;

import org.ligoj.app.resource.plugin.CurlProcessor;
import org.ligoj.app.resource.plugin.CurlRequest;
import org.ligoj.app.resource.plugin.HttpResponseCallback;

import lombok.Setter;

/**
 * vCloud Curl processor.
 */
public class VCloudCurlProcessor extends CurlProcessor {

	/**
	 * Special callback for vCloud login check.
	 */
	public static final HttpResponseCallback LOGIN_CALLBACK = new VCloudLoginHttpResponseCallback();

	/**
	 * Token used to authenticate request
	 */
	@Setter
	protected String token;

	@Override
	protected boolean process(final CurlRequest request) {
		// Add headers for SSO
		request.getHeaders().put("x-vcloud-authorization", token);
		request.getHeaders().put("Accept", "application/*+xml;version=5.1");
		return super.process(request);
	}

}
