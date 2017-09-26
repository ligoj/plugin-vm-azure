package org.ligoj.app.plugin.vm.azure;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Azure Virtual machine query list.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AzureVmList {

	/**
	 * Found VMs
	 */
	private List<AzureVmEntry> value;

	/**
	 * Azure VM wrapper
	 */
	@Getter
	@Setter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class AzureVmEntry {

		private AzureVmDetails properties;
		private String name;
		private String location;
	}

	/**
	 * Azure VM details.
	 */
	@Getter
	@Setter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class AzureVmDetails {
		private String vmId;
		private Map<String, String> hardwareProfile;
		private AzureVmStorageProfile storageProfile;
		private InstanceView instanceView;
	}

	/**
	 * Azure VM Image.
	 */
	@Getter
	@Setter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class AzureVmStorageProfile {
		private AzureVmOs imageReference;
	}

	/**
	 * Azure VM OS.
	 */
	@Getter
	@Setter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class AzureVmOs {
		private String publisher;
		private String offer;
		private String sku;
	}

	/**
	 * Azure VM statuses.
	 */
	@Getter
	@Setter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class InstanceView {
		private List<VmStatus> statuses;
	}

	/**
	 * Azure VM status.
	 */
	@Getter
	@Setter
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class VmStatus {
		private String code;
	}
}
