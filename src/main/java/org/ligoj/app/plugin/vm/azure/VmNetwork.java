package org.ligoj.app.plugin.vm.azure;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * VM NIC
 */
@Getter
@AllArgsConstructor
public class VmNetwork {
	/**
	 * The network tag. Can be public, private, <code>null</code> or something
	 * else.
	 */
	private String type;

	/**
	 * The resolved IP.
	 */
	private String ip;

	/**
	 * The optional DNS.
	 */
	private String dns;

}
