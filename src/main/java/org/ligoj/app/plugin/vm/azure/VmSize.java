/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.azure;

import java.io.Serializable;

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
@JsonIgnoreProperties(ignoreUnknown = true)
@RequiredArgsConstructor
@NoArgsConstructor
public class VmSize implements Serializable {

	/**
	 * SID
	 */
	private static final long serialVersionUID = 1L;

	@NonNull
	private String name;
	private int numberOfCores;
	private int memoryInMB;
}
