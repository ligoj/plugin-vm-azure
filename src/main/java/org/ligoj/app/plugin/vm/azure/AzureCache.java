/*******************************************************************************
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 *******************************************************************************/
package org.ligoj.app.plugin.vm.azure;

import java.util.function.Function;

import org.ligoj.bootstrap.resource.system.cache.CacheManagerAware;
import org.springframework.stereotype.Component;

import com.hazelcast.cache.HazelcastCacheManager;
import com.hazelcast.config.CacheConfig;

/**
 * "Azure" VM types cache configuration.
 */
@Component
public class AzureCache implements CacheManagerAware {

	@Override
	public void onCreate(final HazelcastCacheManager cacheManager, final Function<String, CacheConfig<?, ?>> provider) {
		cacheManager.createCache("azure-sizes", provider.apply("azure-sizes"));
	}

}
