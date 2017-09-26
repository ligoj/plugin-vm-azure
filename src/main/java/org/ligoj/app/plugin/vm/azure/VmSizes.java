package org.ligoj.app.plugin.vm.azure;

import java.util.Collection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Azure VM sizes.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown=true)
public class VmSizes {
	private Collection<VmSize> value;
}
