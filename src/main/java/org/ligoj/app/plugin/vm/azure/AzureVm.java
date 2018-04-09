/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class AzureVm extends Vm {

	/**
	 * SID
	 */
	private static final long serialVersionUID = 1L;
	private String location;
	private String internalId;

	/**
	 * Disk size, GB
	 */
	private int disk;
}
