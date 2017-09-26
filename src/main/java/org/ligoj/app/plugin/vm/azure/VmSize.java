package org.ligoj.app.plugin.vm.azure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/**
 * Azure VM size.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown=true)
@RequiredArgsConstructor
@NoArgsConstructor
public class VmSize {
	@NonNull
	private String name;
	private int numberOfCores;
	private int memoryInMB;
}
