/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vmazure;

import com.hazelcast.cache.HazelcastCacheManager;
import org.ligoj.bootstrap.resource.system.cache.CacheConfigurer;
import org.ligoj.bootstrap.resource.system.cache.CacheManagerAware;
import org.springframework.stereotype.Component;

/**
 * "Azure" VM types cache configuration.
 */
@Component
public class AzureCache implements CacheManagerAware {

	@Override
	public void onCreate(final HazelcastCacheManager cacheManager, final CacheConfigurer configurer) {
		cacheManager.createCache("azure-sizes", configurer.newCacheConfig("azure-sizes"));
	}

}
