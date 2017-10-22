package org.ligoj.app.plugin.vm.azure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Azure Public IP configuration.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AzurePublicIp {

	private AzurePipProperties properties;

	/**
	 * Azure Public IP properties
	 */
	@Getter
	@Setter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class AzurePipProperties {
		private String ipAddress;
		private AzureDns dnsSettings;
	}

	/**
	 * Azure DSN
	 */
	@Getter
	@Setter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class AzureDns {
		private String fqdn;
	}

}
