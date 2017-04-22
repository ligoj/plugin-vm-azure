package org.ligoj.app.plugin.vm.azure;

import org.ligoj.app.plugin.vm.model.VmStatus;
import org.ligoj.bootstrap.core.DescribedBean;

import lombok.Getter;
import lombok.Setter;

/**
 * Virtual machine description.
 */
@Getter
@Setter
public class Vm extends DescribedBean<String> {

	private String storageProfileName;
	private VmStatus status;
	private int numberOfCpus;
	private boolean busy;
	private boolean deployed;
	private String containerName;
	private int memoryMB;
}
