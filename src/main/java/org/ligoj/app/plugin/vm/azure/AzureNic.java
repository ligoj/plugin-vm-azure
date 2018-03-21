/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.azure;

import java.util.Collection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Azure NIC configuration.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AzureNic {

	private AzureNicProperties properties;

	/**
	 * Azure NIC properties
	 */
	@Getter
	@Setter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class AzureNicProperties {
		private Collection<AzureIpConfiguration> ipConfigurations;
	}

	/**
	 * Azure IP configuration
	 */
	@Getter
	@Setter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class AzureIpConfiguration {
		private AzureIpConfigurationProperties properties;
	}

	/**
	 * Azure IP configuration properties
	 */
	@Getter
	@Setter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class AzureIpConfigurationProperties {
		private String privateIPAddress;
		private AzurePublicIpRef publicIPAddress;
	}

	/**
	 * Azure Public IP
	 */
	@Getter
	@Setter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class AzurePublicIpRef {
		private String id;
	}
}
