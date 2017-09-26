package org.ligoj.app.plugin.vm.azure;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.cache.annotation.CacheKey;
import javax.cache.annotation.CacheResult;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.apache.commons.lang3.StringUtils;
import org.ligoj.app.api.SubscriptionStatusWithData;
import org.ligoj.app.dao.NodeRepository;
import org.ligoj.app.plugin.vm.VmResource;
import org.ligoj.app.plugin.vm.VmServicePlugin;
import org.ligoj.app.plugin.vm.azure.AzureVmList.AzureVmDetails;
import org.ligoj.app.plugin.vm.azure.AzureVmList.AzureVmEntry;
import org.ligoj.app.plugin.vm.azure.AzureVmList.AzureVmOs;
import org.ligoj.app.plugin.vm.dao.VmScheduleRepository;
import org.ligoj.app.plugin.vm.model.VmOperation;
import org.ligoj.app.plugin.vm.model.VmStatus;
import org.ligoj.app.resource.plugin.AbstractXmlApiToolPluginResource;
import org.ligoj.app.resource.plugin.CurlCacheToken;
import org.ligoj.app.resource.plugin.CurlProcessor;
import org.ligoj.app.resource.plugin.CurlRequest;
import org.ligoj.bootstrap.core.SpringUtils;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.ligoj.bootstrap.core.security.SecurityHelper;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import org.ligoj.bootstrap.resource.system.configuration.ConfigurationResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.aad.adal4j.AuthenticationContext;
import com.microsoft.aad.adal4j.ClientCredential;

import lombok.extern.slf4j.Slf4j;

/**
 * Azure VM resource.
 */
@Path(VmAzurePluginResource.URL)
@Service
@Produces(MediaType.APPLICATION_JSON)
@Slf4j
public class VmAzurePluginResource extends AbstractXmlApiToolPluginResource implements VmServicePlugin {

	/**
	 * Plug-in key.
	 */
	public static final String URL = VmResource.SERVICE_URL + "/azure";

	/**
	 * Plug-in key.
	 */
	public static final String KEY = URL.replace('/', ':').substring(1);

	/**
	 * Subscription identifier. Like : "00000000-0000-0000-0000-00000000"
	 */
	public static final String PARAMETER_SUBSCRIPTION = KEY + ":subscription";

	/**
	 * Application/client identifier, used as principal id. Like :
	 * "00000000-0000-0000-0000-00000000"
	 */
	public static final String PARAMETER_APPID = KEY + ":application";

	/**
	 * A valid API key. Would be used to retrieve a session token.
	 */
	public static final String PARAMETER_KEY = KEY + ":key";

	/**
	 * Tenant ID from the directory identifier for sample.
	 * 
	 * @see https://portal.azure.com/#blade/Microsoft_AAD_IAM/ActiveDirectoryMenuBlade/Properties
	 */
	public static final String PARAMETER_TENANT = KEY + ":tenant";

	/**
	 * The resource group name (not object identifier) used to filter the
	 * available VM.
	 */
	public static final String PARAMETER_RESOURCE_GROUP = KEY + ":resource-group";

	/**
	 * The managed VM name, not the VM identifier (vmid). Note that VM
	 * identifier cannot be used to filter resources... Nevertheless, both ID
	 * and name can be used to find a VM during the subscription.
	 */
	public static final String PARAMETER_VM = KEY + ":name";

	/**
	 * API version configuration name
	 */
	public static final String CONF_API_VERSION = KEY + ":api";

	/**
	 * Default API version for "Microsoft.Compute" provider.
	 */
	public static final String DEFAULT_API_VERSION = "2017-03-30";

	/**
	 * Authority token provider end-point URL.
	 */
	private static final String CONF_AUTHORITY = KEY + ":authority";

	/**
	 * Default authority URL end-point as API token provider.
	 */
	public static final String DEFAULT_AUTHORITY = "https://login.windows.net/";

	/**
	 * Management URL.
	 */
	private static final String CONF_MANAGEMENT_URL = KEY + ":management";

	/**
	 * Default management URL end-point.
	 */
	private static final String DEFAULT_MANAGEMENT_URL = "https://management.azure.com/";

	/**
	 * REST URL format for "Microsoft.Compute" provider.
	 */
	private static final String COMPUTE_URL = "subscriptions/{subscriptionId}/resourceGroups/{resourceGroup}/providers/Microsoft.Compute/virtualMachines";

	/**
	 * REST URL format for virtual machine URLs.
	 */
	private static final String SIZES_URL = "subscriptions/{subscriptionId}/providers/Microsoft.Compute/locations/{location}/vmSizes?api-version={apiVersion}";

	/**
	 * REST URL format for a VM.
	 */
	private static final String VM_URL = COMPUTE_URL + "/{vm}?$expand=instanceView&api-version={apiVersion}";

	/**
	 * REST URL format to list VM within a resource group.
	 */
	private static final String FIND_VM_URL = COMPUTE_URL + "?api-version={apiVersion}";

	/**
	 * VM operation POST URL.
	 */
	private static final String OPERATION_VM = COMPUTE_URL + "/{vm}/{operation}?api-version={apiVersion}";

	private static final Map<VmOperation, String> OPERATION_TO_AZURE = new EnumMap<>(VmOperation.class);

	static {
		// OFF and SHUTDOWN are both, OS way first, then hard way.
		OPERATION_TO_AZURE.put(VmOperation.OFF, "powerOff");
		OPERATION_TO_AZURE.put(VmOperation.SHUTDOWN, "powerOff");

		OPERATION_TO_AZURE.put(VmOperation.ON, "start");

		// Restart and reboot are both, OS way first, then hard way.
		OPERATION_TO_AZURE.put(VmOperation.REBOOT, "restart");
		OPERATION_TO_AZURE.put(VmOperation.RESET, "restart");

		// No SUSPEND operation
	}

	/**
	 * Mapping table giving the operation to execute depending on the requested
	 * operation and the status of the VM.
	 * <TABLE summary="Mapping Table">
	 * <THEAD>
	 * <TR>
	 * <TH>status</TH>
	 * <TH>requested operation</TH>
	 * <TH>executed operation</TH>
	 * </TR>
	 * </THEAD> <TBODY>
	 * <TR>
	 * <TD>off</TD>
	 * <TD>shutdown</TD>
	 * <TD>-</TD>
	 * </TR>
	 * <TR>
	 * <TD>off</TD>
	 * <TD>power off</TD>
	 * <TD>-</TD>
	 * </TR>
	 * <TR>
	 * <TD>off</TD>
	 * <TD>resume</TD>
	 * <TD>resume</TD>
	 * </TR>
	 * <TR>
	 * <TD>off</TD>
	 * <TD>suspend</TD>
	 * <TD>-</TD>
	 * </TR>
	 * <TR>
	 * <TD>off</TD>
	 * <TD>reset</TD>
	 * <TD>resume</TD>
	 * </TR>
	 * <TR>
	 * <TD>off</TD>
	 * <TD>restart</TD>
	 * <TD>resume</TD>
	 * </TR>
	 * <TR>
	 * <TD>on</TD>
	 * <TD>shutdown</TD>
	 * <TD>shutdown</TD>
	 * </TR>
	 * <TR>
	 * <TD>on</TD>
	 * <TD>power off</TD>
	 * <TD>power off</TD>
	 * </TR>
	 * <TR>
	 * <TD>on</TD>
	 * <TD>resume</TD>
	 * <TD>-</TD>
	 * </TR>
	 * <TR>
	 * <TD>on</TD>
	 * <TD>suspend</TD>
	 * <TD>-</TD>
	 * </TR>
	 * <TR>
	 * <TD>on</TD>
	 * <TD>reset</TD>
	 * <TD>reset</TD>
	 * </TR>
	 * <TR>
	 * <TD>on</TD>
	 * <TD>restart</TD>
	 * <TD>restart</TD>
	 * </TR>
	 * </TBODY>
	 * </TABLE>
	 */
	private static final Map<VmStatus, Map<VmOperation, VmOperation>> FAILSAFE_OPERATIONS = new EnumMap<>(VmStatus.class);

	/**
	 * Register a mapping Status+operation to operation.
	 * 
	 * @param status
	 *            The current status.
	 * @param operation
	 *            The requested operation
	 * @param operationFailSafe
	 *            The computed operation.
	 */
	private static void registerOperation(final VmStatus status, final VmOperation operation, final VmOperation operationFailSafe) {
		FAILSAFE_OPERATIONS.computeIfAbsent(status, s -> new EnumMap<>(VmOperation.class));
		FAILSAFE_OPERATIONS.get(status).put(operation, operationFailSafe);
	}

	/**
	 * Busy provisioning code suffix, yes it's ugly, but lazy.
	 */
	public static final String BUSY_CODE = "ing";
	/**
	 * Undeployed provisioning code.
	 */
	public static final String UNDEPLOYED_CODE = "PowerState/deallocated";

	/**
	 * VM code to {@link VmStatus} mapping.
	 * 
	 * @see https://docs.microsoft.com/en-us/rest/api/compute/virtualmachines/virtualmachines-state
	 */
	public static final Map<String, VmStatus> CODE_TO_STATUS = new HashMap<>();
	static {
		CODE_TO_STATUS.put("PowerState/running", VmStatus.POWERED_ON);
		CODE_TO_STATUS.put("PowerState/starting", VmStatus.POWERED_ON);
		CODE_TO_STATUS.put("PowerState/stopped", VmStatus.POWERED_OFF);
		CODE_TO_STATUS.put("PowerState/deallocated", VmStatus.POWERED_OFF);
		CODE_TO_STATUS.put("PowerState/deallocating", VmStatus.POWERED_OFF);
		CODE_TO_STATUS.put("PowerState/stopping", VmStatus.POWERED_OFF);
	}

	static {
		// Powered off status
		registerOperation(VmStatus.POWERED_OFF, VmOperation.ON, VmOperation.ON);
		registerOperation(VmStatus.POWERED_OFF, VmOperation.RESET, VmOperation.ON);
		registerOperation(VmStatus.POWERED_OFF, VmOperation.REBOOT, VmOperation.ON);

		// Powered on status
		registerOperation(VmStatus.POWERED_ON, VmOperation.SHUTDOWN, VmOperation.SHUTDOWN);
		registerOperation(VmStatus.POWERED_ON, VmOperation.OFF, VmOperation.OFF);
		registerOperation(VmStatus.POWERED_ON, VmOperation.RESET, VmOperation.RESET);
		registerOperation(VmStatus.POWERED_ON, VmOperation.REBOOT, VmOperation.REBOOT);
	}

	@Autowired
	private CurlCacheToken curlCacheToken;

	@Autowired
	private VmScheduleRepository vmScheduleRepository;

	@Autowired
	private SecurityHelper securityHelper;

	@Autowired
	private ConfigurationResource configuration;

	@Autowired
	private NodeRepository nodeRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@Value("${saas.service-vm-azure-auth-retries:2}")
	private int retries;

	/**
	 * Cache the API token.
	 */
	protected String authenticate(final String tenant, final String principal, final String key) {
		return curlCacheToken.getTokenCache(VmAzurePluginResource.class, tenant + "##" + principal + "/" + key, k -> {

			// Authentication request
			return getAccessTokenFromUserCredentials(tenant, principal, key);
		}, retries, () -> new ValidationJsonException(PARAMETER_KEY, "azure-login"));
	}

	/**
	 * Get the Azure bearer token from the authority.
	 */
	private String getAccessTokenFromUserCredentials(final String tenant, final String principal, final String key) {
		ExecutorService service = null;
		try {
			service = newExecutorService();
			final AuthenticationContext context = newAuthenticationContext(tenant, service);
			/*
			 * Replace {client_id} with ApplicationID and {password} with
			 * password that were used to create Service Principal above.
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

	protected ExecutorService newExecutorService() {
		return Executors.newFixedThreadPool(1);
	}

	/**
	 * Create a new {@link AuthenticationContext}
	 */
	protected AuthenticationContext newAuthenticationContext(final String tenant, final ExecutorService service)
			throws MalformedURLException {
		return new AuthenticationContext(getAuthority() + tenant, true, service);
	}

	private String getAuthority() {
		return configuration.get(CONF_AUTHORITY, DEFAULT_AUTHORITY);
	}

	private String getManagementUrl() {
		return configuration.get(CONF_MANAGEMENT_URL, DEFAULT_MANAGEMENT_URL);
	}

	/**
	 * Return the API version used to query the Azure REST API.
	 */
	private String getApiVersion() {
		return configuration.get(CONF_API_VERSION, DEFAULT_API_VERSION);
	}

	/**
	 * Prepare an authenticated connection to Azure. The given processor would
	 * be updated with the security token.
	 */
	protected void authenticate(final Map<String, String> parameters, final AzureCurlProcessor processor) {
		final String principal = parameters.get(PARAMETER_APPID);
		final String key = StringUtils.trimToEmpty(parameters.get(PARAMETER_KEY));
		final String tenant = StringUtils.trimToEmpty(parameters.get(PARAMETER_TENANT));

		// Authentication request using cache
		processor.setToken(authenticate(tenant, principal, key));
	}

	@Override
	public void link(final int subscription) throws Exception {
		// Validate the virtual machine name
		validateVm(subscriptionResource.getParameters(subscription));
	}

	/**
	 * Find the virtual machines matching to the given criteria. Look into
	 * virtual machine name only.
	 *
	 * @param node
	 *            the node to be tested with given parameters.
	 * @param criteria
	 *            the search criteria. Case is insensitive.
	 * @return virtual machines.
	 */
	@GET
	@Path("{node:[a-z].*}/{criteria}")
	@Consumes(MediaType.APPLICATION_JSON)
	public List<AzureVm> findAllByName(@PathParam("node") final String node, @PathParam("criteria") final String criteria)
			throws IOException {
		// Check the node exists
		if (nodeRepository.findOneVisible(node, securityHelper.getLogin()) == null) {
			return Collections.emptyList();
		}

		// Get all VMs and then filter by its name or id
		final Map<String, String> parameters = pvResource.getNodeParameters(node);
		final String vmJson = StringUtils.defaultString(getAzureResource(parameters, FIND_VM_URL), "{\"value\":[]}");
		final AzureVmList azure = objectMapper.readValue(vmJson, AzureVmList.class);
		return azure.getValue().stream().filter(vm -> StringUtils.containsIgnoreCase(vm.getName(), criteria)).map(v -> toVm(v, null))
				.sorted().collect(Collectors.toList());
	}

	/**
	 * Return the fail-safe {@link VmSize} corresponding to the requested type.
	 */
	private VmSize toVmSize(final Map<String, String> parameters, final String azSub, final VmAzurePluginResource that, final String type,
			final String location) {
		try {
			return that.getInstanceSizes(azSub, location, parameters).getOrDefault(type, new VmSize(type));
		} catch (final IOException ioe) {
			// Unmanaged size for this subscription
			return new VmSize(type);
		}
	}

	/**
	 * Build a described {@link AzureVm} completing the VM details with the
	 * instance details.
	 * 
	 * @param azureVm
	 *            The Azure VM object built from the raw JSON stream.
	 * @param sizeProvider
	 *            Optional Azure instance size provider.
	 * @return The merge VM details.
	 */
	private AzureVm toVm(final AzureVmEntry azureVm, final BiFunction<String, String, VmSize> sizeProvider) {
		final AzureVm result = new AzureVm();
		final AzureVmDetails properties = azureVm.getProperties();
		result.setId(azureVm.getName()); // ID is the name for Azure
		result.setInternalId(properties.getVmId());
		result.setName(azureVm.getName());
		result.setLocation(azureVm.getLocation());

		// Instance type details if available
		if (sizeProvider != null) {
			final String instanceType = properties.getHardwareProfile().get("vmSize");
			final VmSize size = sizeProvider.apply(instanceType, azureVm.getLocation());
			result.setCpu(size.getNumberOfCores());
			result.setRam(size.getMemoryInMB());
		}

		// OS
		final AzureVmOs image = properties.getStorageProfile().getImageReference();
		result.setOs(image.getOffer() + " " + image.getSku() + " " + image.getPublisher());
		return result;
	}

	/**
	 * Validate the VM configuration.
	 * 
	 * @param parameters
	 *            the space parameters.
	 * @return Virtual Machine description.
	 */
	protected AzureVm validateVm(final Map<String, String> parameters) throws IOException {
		return toVm(getAzureVm(parameters), null);
	}

	@Override
	public AzureVm getVmDetails(final Map<String, String> parameters) throws IOException {
		// Get simple VM details
		final AzureVmEntry azure = getAzureVm(parameters);

		// Get instance details
		final String azSub = parameters.get(PARAMETER_SUBSCRIPTION);
		final VmAzurePluginResource that = SpringUtils.getBean(VmAzurePluginResource.class);
		final BiFunction<String, String, VmSize> sizes = (t, l) -> toVmSize(parameters, azSub, that, t, l);
		return toVmStatus(azure, sizes);
	}

	/**
	 * Validate and return the {@link AzureVmEntry} without instance details.
	 * 
	 * @param parameters
	 *            the space parameters.
	 * @return Azure Virtual Machine description.
	 */
	protected AzureVmEntry getAzureVm(final Map<String, String> parameters) throws IOException {

		// Get all VMs and then filter by its name or id
		final String name = parameters.get(PARAMETER_VM);
		final String vmJson = getAzureResource(parameters, VM_URL.replace("{vm}", name));

		// Check the VM has been found
		if (vmJson == null) {
			// Invalid id
			throw new ValidationJsonException(PARAMETER_VM, "azure-vm", name);
		}

		// VM as been found, return the details with status
		return objectMapper.readValue(vmJson, AzureVmEntry.class);
	}

	/**
	 * Build a described {@link AzureVm} bean the JSON VM instance view.
	 */
	private AzureVm toVmStatus(final AzureVmEntry azureVm, BiFunction<String, String, VmSize> sizeProvider) {
		final AzureVm result = toVm(azureVm, sizeProvider);
		final AzureVmDetails properties = azureVm.getProperties();

		// State
		final List<AzureVmList.VmStatus> statuses = properties.getInstanceView().getStatuses();
		result.setStatus(getStatus(statuses));
		result.setBusy(isBusy(statuses));
		result.setDeployed(isDeployed(statuses));
		return result;
	}

	/**
	 * Return the generic VM status from the Azure statuses
	 */
	private VmStatus getStatus(final List<AzureVmList.VmStatus> statuses) {
		return statuses.stream().map(AzureVmList.VmStatus::getCode).map(CODE_TO_STATUS::get).filter(Objects::nonNull).findFirst()
				.orElse(null);
	}

	/**
	 * Return the generic VM status from the Azure statuses
	 */
	private boolean isBusy(final List<AzureVmList.VmStatus> statuses) {
		return statuses.stream().map(AzureVmList.VmStatus::getCode).filter(c -> c.startsWith("ProvisioningState"))
				.anyMatch(c -> c.endsWith(BUSY_CODE));
	}

	/**
	 * Return the generic VM status from the Azure statuses
	 */
	private boolean isDeployed(final List<AzureVmList.VmStatus> statuses) {
		return statuses.stream().map(AzureVmList.VmStatus::getCode).filter(UNDEPLOYED_CODE::equals).findFirst().isPresent();
	}

	/**
	 * Return a vCloud's resource after an authentication. Return
	 * <code>null</code> when the resource is not found. Authentication will be
	 * done to get the data.
	 */
	protected String getAzureResource(final Map<String, String> parameters, final String resource) {
		return authenticateAndExecute(parameters, HttpMethod.GET, resource);
	}

	/**
	 * Return an Azure resource after an authentication. Return
	 * <code>null</code> when the resource is not found. Authentication is
	 * requested using a token from a cache.
	 */
	protected String authenticateAndExecute(final Map<String, String> parameters, final String method, final String resource) {
		final AzureCurlProcessor processor = new AzureCurlProcessor();
		authenticate(parameters, processor);
		return execute(processor, method, buildUrl(parameters, resource), "");
	}

	/**
	 * Build a fully qualified management URL from the target resource and the
	 * subscription parameters. Replace resourceGroup, apiVersion, subscription,
	 * and VM name when available within the resource URL.
	 * 
	 * @param resource
	 *            Resource URL with parameters to replace.
	 */
	private String buildUrl(final Map<String, String> parameters, final String resource) {
		return getManagementUrl() + resource.replace("{apiVersion}", getApiVersion())
				.replace("{resourceGroup}", parameters.getOrDefault(PARAMETER_RESOURCE_GROUP, "-"))
				.replace("{subscriptionId}", parameters.getOrDefault(PARAMETER_SUBSCRIPTION, "-"))
				.replace("{vm}", parameters.getOrDefault(PARAMETER_VM, "-"));
	}

	/**
	 * Return/execute a vCloud resource. Return <code>null</code> when the
	 * resource is not found. Authentication should be proceeded before for
	 * authenticated query.
	 */
	protected String execute(final CurlProcessor processor, final String method, final String url, final String resource) {
		// Get the resource using the preempted authentication
		final CurlRequest request = new CurlRequest(method,
				StringUtils.removeEnd(StringUtils.appendIfMissing(url, "/") + StringUtils.removeStart(resource, "/"), "/"), null);
		request.setSaveResponse(true);

		// Execute the requests
		processor.process(request);
		processor.close();
		return request.getResponse();
	}

	@Override
	public String getKey() {
		return VmAzurePluginResource.KEY;
	}

	/**
	 * Check the server is available with enough permission to query VM.
	 * Requires "VIRTUAL MACHINE CONTRIBUTOR" permission.
	 */
	private void validateAdminAccess(final Map<String, String> parameters) throws Exception {
		if (getAzureResource(parameters, FIND_VM_URL) == null) {
			throw new ValidationJsonException(PARAMETER_SUBSCRIPTION, "azure-admin");
		}
	}

	@Override
	public String getVersion(final Map<String, String> parameters) {
		// Use API version as product version
		return getApiVersion();
	}

	@Override
	public boolean checkStatus(final Map<String, String> parameters) throws Exception {
		// Status is UP <=> Administration access is UP (if defined)
		validateAdminAccess(parameters);
		return true;
	}

	@Override
	public SubscriptionStatusWithData checkSubscriptionStatus(final int subscription, final String node,
			final Map<String, String> parameters) throws Exception { // NOSONAR
		final SubscriptionStatusWithData status = new SubscriptionStatusWithData();
		status.put("vm", getVmDetails(parameters));
		status.put("schedules", vmScheduleRepository.countBySubscription(subscription));
		return status;
	}

	@Override
	public void execute(final int subscription, final VmOperation operation) throws Exception {
		final Map<String, String> parameters = subscriptionResource.getParametersNoCheck(subscription);

		// First get VM state
		final AzureVm vm = getVmDetails(parameters);
		final VmStatus status = vm.getStatus();

		// Get the right operation depending on the current state
		final VmOperation operationF = failSafeOperation(status, operation);
		if (operationF == null) {
			// Final operation is considered as useless
			log.info("Requested operation {} is marked as useless considering the status {} of vm {}", operation, status,
					parameters.get(PARAMETER_VM));
			return;
		}

		// Execute the operation
		checkSchedulerResponse(authenticateAndExecute(parameters, HttpMethod.POST,
				OPERATION_VM.replace("{operation}", OPERATION_TO_AZURE.get(operationF))));
	}

	/**
	 * Check the response is valid. For now, the response must not be
	 * <code>null</code>.
	 */
	private void checkSchedulerResponse(final String response) {
		if (response == null) {
			// The result is not correct
			throw new BusinessException("vm-operation-execute");
		}
	}

	/**
	 * Decide the best operation suiting to the required operation and depending
	 * on the current status of the virtual machine.
	 * 
	 * @param status
	 *            The current status of the VM.
	 * @param operation
	 *            The requested operation.
	 * @return The fail-safe operation suiting to the current status of the VM.
	 *         Return <code>null</code> when the computed operation is
	 *         irrelevant.
	 */
	protected VmOperation failSafeOperation(final VmStatus status, final VmOperation operation) {
		return Optional.ofNullable(FAILSAFE_OPERATIONS.get(status)).map(m -> m.get(operation)).orElse(null);
	}

	/**
	 * Return the available Azure sizes.
	 * 
	 * @param azSub
	 *            The related Azure subscription identifier. Seem to duplicate
	 *            the one inside the given parameters, but required for the
	 *            cache key.
	 * @param location
	 *            The target location, required by Azure web service
	 * @param parameters
	 *            The credentials parameters
	 */
	@CacheResult(cacheName = "azure-sizes")
	public Map<String, VmSize> getInstanceSizes(@CacheKey final String azSub, @CacheKey final String location,
			final Map<String, String> parameters) throws IOException {
		final String jsonSizes = getAzureResource(parameters, SIZES_URL.replace("{subscriptionId}", azSub).replace("{location}", location));
		return objectMapper.readValue(StringUtils.defaultString(jsonSizes, "{\"value\":[]}"), VmSizes.class).getValue().stream()
				.collect(Collectors.toMap(VmSize::getName, Function.identity()));
	}
}