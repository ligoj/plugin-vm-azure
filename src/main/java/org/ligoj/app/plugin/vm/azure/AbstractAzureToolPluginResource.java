/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.azure;

import java.net.MalformedURLException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.ws.rs.HttpMethod;

import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.resource.plugin.AbstractToolPluginResource;
import org.ligoj.app.resource.plugin.CurlCacheToken;
import org.ligoj.app.resource.plugin.CurlProcessor;
import org.ligoj.app.resource.plugin.CurlRequest;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import org.ligoj.bootstrap.resource.system.configuration.ConfigurationResource;
import org.springframework.beans.factory.annotation.Autowired;

import com.microsoft.aad.adal4j.AuthenticationContext;
import com.microsoft.aad.adal4j.ClientCredential;

import lombok.extern.slf4j.Slf4j;

/**
 * The goal of this class is sharing some Azure utilities among multiple plug-ins. But, for now, there is no plug-in
 * dependency management.
 */
@Slf4j
public abstract class AbstractAzureToolPluginResource extends AbstractToolPluginResource {

	/**
	 * Plug-in Key shortcut
	 */
	public static final String PLUGIN_KEY = "service:vm:azure";

	/**
	 * API version configuration name
	 */
	public static final String CONF_API_VERSION = PLUGIN_KEY + ":api";

	/**
	 * Default API version for "Microsoft.Compute" provider.
	 */
	public static final String DEFAULT_API_VERSION = "2017-03-30";

	/**
	 * Authentication retries when failed.
	 */
	private static final String CONF_AUTH_RETRIES = PLUGIN_KEY + ":auth-retries";

	/**
	 * Default authentication retries when failed.
	 */
	public static final int DEFAULT_AUTH_RETRIES = 2;

	/**
	 * Authority token provider end-point URL.
	 */
	private static final String CONF_AUTHORITY = PLUGIN_KEY + ":authority";

	/**
	 * Default authority URL end-point as API token provider.
	 */
	public static final String DEFAULT_AUTHORITY = "https://login.windows.net/";

	/**
	 * Management URL.
	 */
	private static final String CONF_MANAGEMENT_URL = PLUGIN_KEY + ":management";

	/**
	 * Default management URL end-point.
	 */
	private static final String DEFAULT_MANAGEMENT_URL = "https://management.azure.com/";

	/**
	 * Subscription identifier. Like : "00000000-0000-0000-0000-00000000"
	 */
	public static final String PARAMETER_SUBSCRIPTION = PLUGIN_KEY + ":subscription";

	/**
	 * Application/client identifier, used as principal id. Like : "00000000-0000-0000-0000-00000000"
	 */
	public static final String PARAMETER_APPID = PLUGIN_KEY + ":application";

	/**
	 * A valid API key. Would be used to retrieve a session token.
	 */
	public static final String PARAMETER_KEY = PLUGIN_KEY + ":key";

	/**
	 * Tenant ID from the directory identifier for sample.
	 * 
	 * @see <a href=
	 *      "https://portal.azure.com/#blade/Microsoft_AAD_IAM/ActiveDirectoryMenuBlade/Properties">ActiveDirectoryMenuBlade</a>
	 */
	public static final String PARAMETER_TENANT = PLUGIN_KEY + ":tenant";

	/**
	 * The resource group name (not object identifier) used to filter the available VM.
	 */
	public static final String PARAMETER_RESOURCE_GROUP = PLUGIN_KEY + ":resource-group";

	/**
	 * REST URL format for "Microsoft.Compute" provider.
	 */
	public static final String COMPUTE_URL = "subscriptions/{subscriptionId}/resourceGroups/{resourceGroup}/providers/Microsoft.Compute/virtualMachines";

	/**
	 * REST URL format to list VM within a resource group.
	 */
	public static final String FIND_VM_URL = COMPUTE_URL + "?api-version={apiVersion}";

	@Autowired
	protected CurlCacheToken curlCacheToken;

	@Autowired
	protected ConfigurationResource configuration;

	/**
	 * Authenticate using the cache API token.
	 * 
	 * @param tenant
	 *            The tenant UID.
	 * @param principal
	 *            The application UID.
	 * @param key
	 *            The token API key.
	 * @param The
	 *            authenticate token.
	 */
	protected String authenticate(final String tenant, final String principal, final String key) {
		// Authentication request
		return curlCacheToken.getTokenCache(AbstractAzureToolPluginResource.class,
				tenant + "##" + principal + "/" + key, k -> getAccessTokenFromUserCredentials(tenant, principal, key),
				getRetries(), () -> new ValidationJsonException(PLUGIN_KEY + ":key", "azure-login"));
	}

	@Override
	public String getVersion(final Map<String, String> parameters) {
		// Use API version as product version
		return getApiVersion();
	}

	/**
	 * Get the Azure bearer token from the authority.
	 */
	private String getAccessTokenFromUserCredentials(final String tenant, final String principal, final String key) {
		final ExecutorService service = newExecutorService();
		try {
			final AuthenticationContext context = newAuthenticationContext(tenant, service);
			/*
			 * Replace {client_id} with ApplicationID and {password} with password that were used to create Service
			 * Principal above.
			 */
			final ClientCredential credential = new ClientCredential(principal, key);
			return context.acquireToken(getManagementUrl(), credential, null).get().getAccessToken();
		} catch (final ExecutionException | InterruptedException | MalformedURLException e) {
			// Authentication failed
			log.info("Azure authentication failed for tenant {} and principal {}", tenant, principal, e);
		} finally {
			service.shutdown();
		}
		return null;
	}

	/**
	 * Create and return a new executor pool service.
	 * 
	 * @return A new executor pool service.
	 */
	protected ExecutorService newExecutorService() {
		return Executors.newFixedThreadPool(1);
	}

	/**
	 * Create a new {@link AuthenticationContext}
	 * 
	 * @param The
	 *            tenant identifier.
	 * @param executor
	 *            service.
	 * @return the new authenticated context.
	 * @throws MalformedURLException
	 *             When authority URL cannot be read.
	 */
	protected AuthenticationContext newAuthenticationContext(final String tenant, final ExecutorService service)
			throws MalformedURLException {
		return new AuthenticationContext(getAuthority() + tenant, true, service);
	}

	/**
	 * Return the authority token provider end-point URL.
	 * 
	 * @return The authority token provider end-point URL.
	 */
	private String getAuthority() {
		return configuration.get(CONF_AUTHORITY, DEFAULT_AUTHORITY);
	}

	/**
	 * Return the authentication retries.
	 * 
	 * @return The authentication retries.
	 */
	protected int getRetries() {
		return configuration.get(CONF_AUTH_RETRIES, DEFAULT_AUTH_RETRIES);
	}

	/**
	 * Return the management URL.
	 * 
	 * @return The management URL.
	 */
	protected String getManagementUrl() {
		return configuration.get(CONF_MANAGEMENT_URL, DEFAULT_MANAGEMENT_URL);
	}

	/**
	 * Return the API version used to query the Azure REST API.
	 * 
	 * @return API version.
	 */
	protected String getApiVersion() {
		return configuration.get(CONF_API_VERSION, DEFAULT_API_VERSION);
	}

	/**
	 * Prepare an authenticated connection to Azure. The given processor would be updated with the security token.
	 * 
	 * @param parameters
	 *            The subscription parameters.
	 * @param processor
	 *            The processor used to authenticate and execute the request.
	 */
	protected void authenticate(final Map<String, String> parameters, final AzureCurlProcessor processor) {
		final String principal = parameters.get(PARAMETER_APPID);
		final String key = StringUtils.trimToEmpty(parameters.get(PARAMETER_KEY));
		final String tenant = StringUtils.trimToEmpty(parameters.get(PARAMETER_TENANT));

		// Authentication request using cache
		processor.setToken(authenticate(tenant, principal, key));
	}

	/**
	 * Return a Azure's resource after an authentication. Authentication will be done to get the data.
	 * 
	 * @param parameters
	 *            The subscription parameters.
	 * @param resource
	 *            The internal resource. Appended to the base management URL. This URL may contain parameters to
	 *            replace. Supported parameters are : <code>{apiVersion}</code>,
	 *            <code>{resourceGroup}</code>,<code>{subscriptionId}</code>.
	 * @return The requested azure resource or <code>null</code> when the resource is not found.
	 */
	protected String getAzureResource(final Map<String, String> parameters, final String resource) {
		return authenticateAndExecute(parameters, HttpMethod.GET, resource);
	}

	/**
	 * Return an Azure resource after an authentication. Return <code>null</code> when the resource is not found.
	 * Authentication is requested using a token from a cache.
	 * 
	 * @param parameters
	 *            The subscription parameters.
	 * @param method
	 *            The HHTTP method.
	 * @param resource
	 *            The internal resource. Appended to the base management URL. This URL may contain parameters to
	 *            replace. Supported parameters are : <code>{apiVersion}</code>,
	 *            <code>{resourceGroup}</code>,<code>{subscriptionId}</code>.
	 * @return The requested azure resource or <code>null</code> when the resource is not found.
	 */
	protected String authenticateAndExecute(final Map<String, String> parameters, final String method,
			final String resource) {
		final AzureCurlProcessor processor = new AzureCurlProcessor();
		authenticate(parameters, processor);
		final String result = execute(processor, method, buildUrl(parameters, resource), "");
		processor.close();
		return result;
	}

	/**
	 * Build a fully qualified management URL from the target resource and the subscription parameters. Replace
	 * resourceGroup, apiVersion, subscription, and VM name when available within the resource URL.
	 * 
	 * @param parameters
	 *            The subscription parameters.
	 * @param resource
	 *            Resource URL with parameters to replace.
	 * @return The target URL with interpolated variables.
	 */
	protected String buildUrl(final Map<String, String> parameters, final String resource) {
		return getManagementUrl() + resource.replace("{apiVersion}", getApiVersion())
				.replace("{resourceGroup}", parameters.getOrDefault(PARAMETER_RESOURCE_GROUP, "-"))
				.replace("{subscriptionId}", parameters.getOrDefault(PARAMETER_SUBSCRIPTION, "-"));
	}

	/**
	 * Return an Azure resource. Return <code>null</code> when the resource is not found. Authentication should be
	 * proceeded before for authenticated query.
	 * 
	 * @param processor
	 *            The processor used to query the resource.
	 * @param method
	 *            The HHTTP method.
	 * @param url
	 *            The base URL.
	 * @param resource
	 *            The internal resource URL appended to the base URL parameter. DUplicate '/' are handled.
	 * @return The requested azure resource or <code>null</code> when the resource is not found.
	 */
	protected String execute(final CurlProcessor processor, final String method, final String url,
			final String resource) {
		// Get the resource using the preempted authentication
		final CurlRequest request = new CurlRequest(method, StringUtils
				.removeEnd(StringUtils.appendIfMissing(url, "/") + StringUtils.removeStart(resource, "/"), "/"), null);
		request.setSaveResponse(true);

		// Execute the requests
		processor.process(request);
		return request.getResponse();
	}

	/**
	 * Check the server is available with enough permission to query VM. Requires "VIRTUAL MACHINE CONTRIBUTOR"
	 * permission.
	 * 
	 * @param parameters
	 *            The subscription parameters.
	 */
	protected void validateAdminAccess(final Map<String, String> parameters) {
		if (getAzureResource(parameters, FIND_VM_URL) == null) {
			throw new ValidationJsonException(PARAMETER_SUBSCRIPTION, "azure-admin");
		}
	}

	@Override
	public boolean checkStatus(final Map<String, String> parameters) {
		// Status is UP <=> Administration access is UP (if defined)
		validateAdminAccess(parameters);
		return true;
	}
}
