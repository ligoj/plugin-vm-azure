package org.ligoj.app.plugin.vm.azure;

import org.ligoj.app.plugin.vm.Vm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Azure Virtual machine description.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown=true)
public class AzureVm extends Vm {

	private String location;
	private String internalId;
	
	/**
	 * Disk size, GB
	 */
	private int disk;
}
