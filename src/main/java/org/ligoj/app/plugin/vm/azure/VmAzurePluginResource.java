package org.ligoj.app.plugin.vm.azure;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import org.ligoj.app.plugin.vm.VmNetwork;
import org.ligoj.app.plugin.vm.VmResource;
import org.ligoj.app.plugin.vm.VmServicePlugin;
import org.ligoj.app.plugin.vm.azure.AzureNic.AzureIpConfiguration;
import org.ligoj.app.plugin.vm.azure.AzureNic.AzureIpConfigurationProperties;
import org.ligoj.app.plugin.vm.azure.AzurePublicIp.AzureDns;
import org.ligoj.app.plugin.vm.azure.AzureVmList.AzureVmDetails;
import org.ligoj.app.plugin.vm.azure.AzureVmList.AzureVmEntry;
import org.ligoj.app.plugin.vm.azure.AzureVmList.AzureVmNicRef;
import org.ligoj.app.plugin.vm.azure.AzureVmList.AzureVmOs;
import org.ligoj.app.plugin.vm.dao.VmScheduleRepository;
import org.ligoj.app.plugin.vm.model.VmOperation;
import org.ligoj.app.plugin.vm.model.VmStatus;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.ligoj.bootstrap.core.security.SecurityHelper;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Azure VM resource.
 */
@Path(VmAzurePluginResource.URL)
@Service
@Produces(MediaType.APPLICATION_JSON)
@Slf4j
public class VmAzurePluginResource extends AbstractAzureToolPluginResource implements VmServicePlugin {

	/**
	 * Plug-in key.
	 */
	public static final String URL = VmResource.SERVICE_URL + "/azure";

	/**
	 * Plug-in key.
	 */
	public static final String KEY = URL.replace('/', ':').substring(1);

	/**
	 * VM operation POST URL.
	 */
	public static final String OPERATION_VM = COMPUTE_URL + "/{vm}/{operation}?api-version={apiVersion}";

	/**
	 * REST URL format for virtual machine URLs.
	 */
	public static final String SIZES_URL = "subscriptions/{subscriptionId}/providers/Microsoft.Compute/locations/{location}/vmSizes?api-version={apiVersion}";

	/**
	 * REST URL format for a VM.
	 */
	public static final String VM_URL = COMPUTE_URL + "/{vm}?$expand=instanceView&api-version={apiVersion}";

	/**
	 * The managed VM name, not the VM identifier (vmid). Note that VM identifier cannot be used to filter resources...
	 * Nevertheless, both ID and name can be used to find a VM during the subscription.
	 */
	public static final String PARAMETER_VM = KEY + ":name";

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
	 * Mapping table giving the operation to execute depending on the requested operation and the status of the VM.
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
	private static final Map<VmStatus, Map<VmOperation, VmOperation>> FAILSAFE_OPERATIONS = new EnumMap<>(
			VmStatus.class);

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
	private static void registerOperation(final VmStatus status, final VmOperation operation,
			final VmOperation operationFailSafe) {
		FAILSAFE_OPERATIONS.computeIfAbsent(status, s -> new EnumMap<>(VmOperation.class));
		FAILSAFE_OPERATIONS.get(status).put(operation, operationFailSafe);
	}

	/**
	 * Busy provisioning code suffix, yes it's ugly, but lazy.
	 */
	private static final String BUSY_CODE = "ing";
	/**
	 * Undeployed provisioning code.
	 */
	private static final String UNDEPLOYED_CODE = "PowerState/deallocated";

	/**
	 * VM code to {@link VmStatus} mapping.
	 * 
	 * @see https://docs.microsoft.com/en-us/rest/api/compute/virtualmachines/virtualmachines-state
	 */
	private static final Map<String, VmStatus> CODE_TO_STATUS = new HashMap<>();
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
	private VmScheduleRepository vmScheduleRepository;

	@Autowired
	private SecurityHelper securityHelper;

	@Autowired
	private NodeRepository nodeRepository;

	@Autowired
	private ObjectMapper objectMapper;
	
	/**
	 * Used for "this" and forcing proxying.
	 */
	@Autowired
	protected VmAzurePluginResource self;

	@Override
	public void link(final int subscription) throws Exception {
		// Validate the virtual machine name
		getAzureVm(subscriptionResource.getParameters(subscription));
	}

	/**
	 * Find the virtual machines matching to the given criteria. Look into virtual machine name only.
	 *
	 * @param node
	 *            the node to be tested with given parameters.
	 * @param criteria
	 *            the search criteria. Case is insensitive.
	 * @return virtual machines.
	 */
	@GET
	@Path("{node:service:.+}/{criteria}")
	@Consumes(MediaType.APPLICATION_JSON)
	public List<AzureVm> findAllByName(@PathParam("node") final String node,
			@PathParam("criteria") final String criteria) throws IOException {
		// Check the node exists
		if (nodeRepository.findOneVisible(node, securityHelper.getLogin()) == null) {
			return Collections.emptyList();
		}

		// Get all VMs and then filter by its name or id
		final Map<String, String> parameters = pvResource.getNodeParameters(node);
		final String vmJson = StringUtils.defaultString(getAzureResource(parameters, FIND_VM_URL), "{\"value\":[]}");
		final AzureVmList azure = objectMapper.readValue(vmJson, AzureVmList.class);
		return azure.getValue().stream().filter(vm -> StringUtils.containsIgnoreCase(vm.getName(), criteria))
				.map(v -> toVm(v, null)).sorted().collect(Collectors.toList());
	}

	/**
	 * Return the fail-safe {@link VmSize} corresponding to the requested type.
	 */
	private VmSize toVmSize(final Map<String, String> parameters, final String azSub, final String type,
			final String location) {
		try {
			return self.getInstanceSizes(azSub, location, parameters).getOrDefault(type, new VmSize(type));
		} catch (final IOException ioe) {
			// Unmanaged size for this subscription
			log.info("Unmanaged VM size {} : {}", type, ioe.getMessage());
			return new VmSize(type);
		}
	}

	/**
	 * Build a described {@link AzureVm} completing the VM details with the instance details.
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
		if (image.getOffer() == null) {
			// From image
			result.setOs(properties.getStorageProfile().getOsDisk().getOsType() + " ("
					+ StringUtils.substringAfterLast(image.getId(), "/") + ")");
		} else {
			// From marketplace : provider
			result.setOs(image.getOffer() + " " + image.getSku() + " " + image.getPublisher());
		}

		// Disk size
		result.setDisk(properties.getStorageProfile().getOsDisk().getDiskSizeGB());

		return result;
	}

	@Override
	public AzureVm getVmDetails(final Map<String, String> parameters) {
		final String name = parameters.get(PARAMETER_VM);
		final AzureCurlProcessor processor = new AzureCurlProcessor();
		try {
			// Associate the oAuth token to the processor
			authenticate(parameters, processor);

			// Get the VM data
			final String vmJson = getVmResource(name, parameters, processor, VM_URL.replace("{vm}", name));

			// VM as been found, return the details with status
			final AzureVmEntry azure = readValue(vmJson, AzureVmEntry.class);

			// Get instance details
			final String azSub = parameters.get(PARAMETER_SUBSCRIPTION);
			final BiFunction<String, String, VmSize> sizes = (t, l) -> toVmSize(parameters, azSub, t, l);
			final AzureVm vm = toVmStatus(azure, sizes);
			vm.setNetworks(new ArrayList<>());

			// Get network data for each network references
			getNetworkDetails(name, parameters, processor,
					azure.getProperties().getNetworkProfile().getNetworkInterfaces(), vm.getNetworks());
			return vm;
		} finally {
			processor.close();
		}
	}

	/**
	 * Check the VM has been found with not <code>null</code> response.
	 */
	private String checkResponse(final String name, final String vmJson) {
		if (vmJson == null) {
			// Invalid VM identifier? This VM cannot be found
			throw new ValidationJsonException(PARAMETER_VM, "azure-vm", name);
		}
		return vmJson;
	}

	/**
	 * Fill the given VM with its network details.
	 */
	private void getNetworkDetails(final String name, final Map<String, String> parameters,
			final AzureCurlProcessor processor, final Collection<AzureVmNicRef> nicRefs,
			final Collection<VmNetwork> networks) {
		nicRefs.stream()
				.map(nicRef -> getVmResource(name, parameters, processor, nicRef.getId() + "?api-version=2017-09-01"))
				// Parse the NIC JSON data and get the details
				.forEach(nicJson -> getNicDetails(name, parameters, processor, readValue(nicJson, AzureNic.class),
						networks));
	}

	private String getVmResource(final String name, final Map<String, String> parameters,
			final AzureCurlProcessor processor, final String resource) {
		return checkResponse(name, execute(processor, "GET", buildUrl(parameters, resource), ""));
	}

	/**
	 * Fill the given VM with its network details.
	 */
	private void getNicDetails(final String name, final Map<String, String> parameters,
			final AzureCurlProcessor processor, final AzureNic nic, final Collection<VmNetwork> networks) {
		// Extract the direct private IP and the indirect public IP
		nic.getProperties().getIpConfigurations().stream().map(AzureIpConfiguration::getProperties)

				// Save the private IP
				.peek(c -> networks.add(new VmNetwork("private", c.getPrivateIPAddress(), null)))

				// Check there is an attached public IP
				.map(AzureIpConfigurationProperties::getPublicIPAddress).filter(Objects::nonNull)

				// Get the public IP json
				.map(id -> getVmResource(name, parameters, processor, id.getId() + "?api-version=2017-09-01"))

				// Parse the public IP JSON data
				.map(i -> readValue(i, AzurePublicIp.class)).map(AzurePublicIp::getProperties)

				// Get the public IP and the optional DNS
				.forEach(i -> networks.add(new VmNetwork("public", i.getIpAddress(),
						Optional.ofNullable(i.getDnsSettings()).map(AzureDns::getFqdn).orElse(null))));
	}

	/**
	 * Parse the String and return a runtime exception whn is not a correct JSON.
	 */
	private <T> T readValue(final String json, final Class<T> clazz) {
		try {
			return objectMapper.readValue(json, clazz);
		} catch (final IOException e) {
			throw new IllegalArgumentException(e);
		}
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
		final String vmJson = checkResponse(name, getAzureResource(parameters, VM_URL.replace("{vm}", name)));

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
		return statuses.stream().map(AzureVmList.VmStatus::getCode).map(CODE_TO_STATUS::get).filter(Objects::nonNull)
				.findFirst().orElse(null);
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
		return statuses.stream().map(AzureVmList.VmStatus::getCode).anyMatch(UNDEPLOYED_CODE::equals);
	}

	@Override
	protected String buildUrl(final Map<String, String> parameters, final String resource) {
		return super.buildUrl(parameters, resource).replace("{vm}", parameters.getOrDefault(PARAMETER_VM, "-"));
	}

	@Override
	public String getKey() {
		return VmAzurePluginResource.KEY;
	}

	@Override
	public SubscriptionStatusWithData checkSubscriptionStatus(final int subscription, final String node,
			final Map<String, String> parameters) {
		final SubscriptionStatusWithData status = new SubscriptionStatusWithData();
		status.put("vm", getVmDetails(parameters));
		status.put("schedules", vmScheduleRepository.countBySubscription(subscription));
		return status;
	}

	@Override
	public void execute(final int subscription, final VmOperation operation) {
		final Map<String, String> parameters = subscriptionResource.getParametersNoCheck(subscription);

		// First get VM state
		final AzureVm vm = getVmDetails(parameters);
		final VmStatus status = vm.getStatus();

		// Get the right operation depending on the current state
		final VmOperation operationF = failSafeOperation(status, operation);
		if (operationF == null) {
			// Final operation is considered as useless
			log.info("Requested operation {} is marked as useless considering the status {} of vm {}", operation,
					status, parameters.get(PARAMETER_VM));
			return;
		}

		// Execute the operation
		checkSchedulerResponse(authenticateAndExecute(parameters, HttpMethod.POST,
				OPERATION_VM.replace("{operation}", OPERATION_TO_AZURE.get(operationF))));
	}

	/**
	 * Check the response is valid. For now, the response must not be <code>null</code>.
	 */
	private void checkSchedulerResponse(final String response) {
		if (response == null) {
			// The result is not correct
			throw new BusinessException("vm-operation-execute");
		}
	}

	/**
	 * Decide the best operation suiting to the required operation and depending on the current status of the virtual
	 * machine.
	 * 
	 * @param status
	 *            The current status of the VM.
	 * @param operation
	 *            The requested operation.
	 * @return The fail-safe operation suiting to the current status of the VM. Return <code>null</code> when the
	 *         computed operation is irrelevant.
	 */
	protected VmOperation failSafeOperation(final VmStatus status, final VmOperation operation) {
		return Optional.ofNullable(FAILSAFE_OPERATIONS.get(status)).map(m -> m.get(operation)).orElse(null);
	}

	/**
	 * Return the available Azure sizes.
	 * 
	 * @param azSub
	 *            The related Azure subscription identifier. Seem to duplicate the one inside the given parameters, but
	 *            required for the cache key.
	 * @param location
	 *            The target location, required by Azure web service
	 * @param parameters
	 *            The credentials parameters
	 */
	@CacheResult(cacheName = "azure-sizes")
	public Map<String, VmSize> getInstanceSizes(@CacheKey final String azSub, @CacheKey final String location,
			final Map<String, String> parameters) throws IOException {
		final String jsonSizes = getAzureResource(parameters,
				SIZES_URL.replace("{subscriptionId}", azSub).replace("{location}", location));
		return objectMapper.readValue(StringUtils.defaultString(jsonSizes, "{\"value\":[]}"), VmSizes.class).getValue()
				.stream().collect(Collectors.toMap(VmSize::getName, Function.identity()));
	}
}
