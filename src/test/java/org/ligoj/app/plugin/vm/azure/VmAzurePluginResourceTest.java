/*
 * Licensed under MIT (https://github.com/ligoj/ligoj/blob/master/LICENSE)
 */
package org.ligoj.app.plugin.vm.azure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import javax.transaction.Transactional;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ligoj.app.AbstractServerTest;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Parameter;
import org.ligoj.app.model.ParameterValue;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.vm.model.VmOperation;
import org.ligoj.app.plugin.vm.model.VmStatus;
import org.ligoj.app.resource.node.ParameterValueResource;
import org.ligoj.app.resource.subscription.SubscriptionResource;
import org.ligoj.bootstrap.MatcherUtil;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import org.ligoj.bootstrap.resource.system.configuration.ConfigurationResource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.support.ExecutorServiceAdapter;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.microsoft.aad.adal4j.AuthenticationContext;
import com.microsoft.aad.adal4j.AuthenticationResult;
import com.microsoft.aad.adal4j.ClientCredential;

/**
 * Test class of {@link VmAzurePluginResource}
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
class VmAzurePluginResourceTest extends AbstractServerTest {
	private static final String COMPUTE_URL = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/group1/providers/Microsoft.Compute/virtualMachines";

	@Autowired
	private VmAzurePluginResource resource;

	@Autowired
	private ParameterValueResource pvResource;

	@Autowired
	private SubscriptionResource subscriptionResource;

	@Autowired
	private ConfigurationResource configuration;

	protected int subscription;

	@BeforeEach
	void prepareData() throws IOException {
		// Only with Spring context
		persistSystemEntities();
		persistEntities("csv",
				new Class[] { Node.class, Parameter.class, Project.class, Subscription.class, ParameterValue.class },
				StandardCharsets.UTF_8.name());
		this.subscription = getSubscription("gStack");

		// Coverage only
		resource.getKey();

		configuration.put("service:vm:azure:management", "http://localhost:" + MOCK_PORT + "/");
		configuration.put("service:vm:azure:authority", "https://localhost:" + MOCK_PORT + "/");

		// Invalidate azure cache
		cacheManager.getCache("curl-tokens").clear();
		cacheManager.getCache("azure-sizes").clear();
	}

	/**
	 * Return the subscription identifier of the given project. Assumes there is only one subscription for a service.
	 */
	private int getSubscription(final String project) {
		return getSubscription(project, VmAzurePluginResource.KEY);
	}

	@Test
	void delete() throws Exception {
		resource.delete(subscription, false);
	}

	@Test
	void getVersion() throws Exception {
		final var version = resource.getVersion(subscription);
		Assertions.assertEquals("2017-03-30", version);
	}

	@Test
	void link() throws Exception {
		prepareMockVm();

		// Invoke create for an already created entity, since for now, there is
		// nothing but validation pour SonarQube
		final var resource = newResource();
		resource.link(this.subscription);

		// Nothing to validate for now...
	}

	@Test
	void getVmDetailsNotFound() throws Exception {
		prepareMockAuth();
		httpServer.start();

		final var resource = newResource();
		final var parameters = pvResource.getNodeParameters("service:vm:azure:test");
		parameters.put(VmAzurePluginResource.PARAMETER_VM, "0");
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> {
			resource.getVmDetails(parameters);
		}), VmAzurePluginResource.PARAMETER_VM, "azure-vm");
	}

	@Test
	void getVmDetails() throws Exception {
		prepareMockVm();

		final var parameters = pvResource.getNodeParameters("service:vm:azure:test");
		parameters.put(VmAzurePluginResource.PARAMETER_VM, "test1");
		final var resource = newResource();
		final var vm = resource.getVmDetails(parameters);
		checkItem(vm);
	}

	private void checkVm(final AzureVm item) {
		checkItem(item);
		Assertions.assertEquals("westeurope", item.getLocation());
		Assertions.assertEquals(VmStatus.POWERED_ON, item.getStatus());
		Assertions.assertEquals(30, item.getDisk());
		Assertions.assertEquals(1, item.getCpu());
		Assertions.assertFalse(item.isBusy());
		Assertions.assertEquals(4048, item.getRam());

		// Check network
		Assertions.assertEquals(2, item.getNetworks().size());
		Assertions.assertEquals("10.0.4.20", item.getNetworks().get(0).getIp());
		Assertions.assertEquals("private", item.getNetworks().get(0).getType());
		Assertions.assertNull(item.getNetworks().get(0).getDns());
		Assertions.assertEquals("1.2.3.4", item.getNetworks().get(1).getIp());
		Assertions.assertEquals("public", item.getNetworks().get(1).getType());
		Assertions.assertEquals("vm-0-b67589.westeurope.cloudapp.azure.com", item.getNetworks().get(1).getDns());
	}

	@Test
	void checkSubscriptionStatus() throws Exception {
		prepareMockVm();
		final var resource = newResource();
		final var nodeStatusWithData = resource.checkSubscriptionStatus(subscription, null,
				subscriptionResource.getParametersNoCheck(subscription));
		Assertions.assertTrue(nodeStatusWithData.getStatus().isUp());
		checkVm((AzureVm) nodeStatusWithData.getData().get("vm"));
	}

	@Test
	void checkSubscriptionStatusFromImage() throws Exception {
		prepareMockAuth();
		prepareMockNetwork();

		// Find a specific VM
		httpServer
				.stubFor(get(urlPathEqualTo(COMPUTE_URL + "/test1")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
						.withBody(IOUtils.toString(
								new ClassPathResource("mock-server/azure/vm-on-from-image.json").getInputStream(),
								StandardCharsets.UTF_8))));

		// Expose VM sizes
		httpServer.stubFor(get(urlPathEqualTo(
				"/subscriptions/00000000-0000-0000-0000-000000000000/providers/Microsoft.Compute/locations/westeurope/vmSizes"))
						.willReturn(aResponse().withStatus(HttpStatus.SC_OK)
								.withBody(IOUtils.toString(
										new ClassPathResource("mock-server/azure/list-sizes.json").getInputStream(),
										StandardCharsets.UTF_8))));
		httpServer.start();

		final var resource = newResource();
		final var nodeStatusWithData = resource.checkSubscriptionStatus(subscription, null,
				subscriptionResource.getParametersNoCheck(subscription));
		Assertions.assertTrue(nodeStatusWithData.getStatus().isUp());

		final var vm = (AzureVm) nodeStatusWithData.getData().get("vm");

		Assertions.assertEquals("vm-id-0", vm.getInternalId());
		Assertions.assertEquals("test1", vm.getId());
		Assertions.assertEquals("Linux (debian9-docker17)", vm.getOs());
	}

	@Test
	void checkSubscriptionStatusInvalidJson() throws Exception {
		prepareMockAuth();
		prepareMockNetwork();

		// Find a specific VM
		httpServer.stubFor(get(urlPathEqualTo(COMPUTE_URL + "/test1"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("<html>")));
		httpServer.start();

		final var resource = newResource();
		Assertions.assertThrows(IllegalArgumentException.class, () -> {
			resource.checkSubscriptionStatus(subscription, null,
					subscriptionResource.getParametersNoCheck(subscription));
		});
	}

	@Test
	void checkSubscriptionStatusNoPublicIp() throws Exception {
		prepareMockAuth();

		// Find a specific VM
		httpServer
				.stubFor(
						get(urlPathEqualTo(COMPUTE_URL + "/test1")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
								.withBody(IOUtils.toString(
										new ClassPathResource("mock-server/azure/vm-on.json").getInputStream(),
										StandardCharsets.UTF_8))));

		// Expose VM sizes
		httpServer.stubFor(get(urlPathEqualTo(
				"/subscriptions/00000000-0000-0000-0000-000000000000/providers/Microsoft.Compute/locations/westeurope/vmSizes"))
						.willReturn(aResponse().withStatus(HttpStatus.SC_OK)
								.withBody(IOUtils.toString(
										new ClassPathResource("mock-server/azure/list-sizes.json").getInputStream(),
										StandardCharsets.UTF_8))));

		// Only private IP
		httpServer.stubFor(get(urlPathEqualTo(
				"/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/group1/providers/Microsoft.Network/networkInterfaces/test1637"))
						.willReturn(aResponse().withStatus(HttpStatus.SC_OK)
								.withBody(IOUtils.toString(
										new ClassPathResource("mock-server/azure/vm-nic.json").getInputStream(),
										StandardCharsets.UTF_8))));

		httpServer.start();

		final var resource = newResource();
		final var nodeStatusWithData = resource.checkSubscriptionStatus(subscription, null,
				subscriptionResource.getParametersNoCheck(subscription));
		Assertions.assertTrue(nodeStatusWithData.getStatus().isUp());

		final var vm = (AzureVm) nodeStatusWithData.getData().get("vm");

		Assertions.assertEquals("vm-id-0", vm.getInternalId());
		Assertions.assertEquals("test1", vm.getId());
		Assertions.assertEquals("UbuntuServer 16.04-LTS Canonical", vm.getOs());
		Assertions.assertEquals(1, vm.getNetworks().size());
		Assertions.assertEquals("10.0.4.20", vm.getNetworks().get(0).getIp());
		Assertions.assertNull(vm.getNetworks().get(0).getDns());
	}

	@Test
	void checkSubscriptionStatusNoSize() throws Exception {
		prepareMockAuth();
		prepareMockNetwork();

		// Find a specific VM
		httpServer
				.stubFor(get(urlPathEqualTo(COMPUTE_URL + "/test1")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
						.withBody(IOUtils.toString(
								new ClassPathResource("mock-server/azure/vm-stoping.json").getInputStream(),
								StandardCharsets.UTF_8))));

		// Expose VM sizes
		httpServer.stubFor(get(urlPathEqualTo(
				"/subscriptions/00000000-0000-0000-0000-000000000000/providers/Microsoft.Compute/locations/westeurope/vmSizes"))
						.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils.toString(
								new ClassPathResource("mock-server/azure/list-sizes-empty.json").getInputStream(),
								StandardCharsets.UTF_8))));
		httpServer.start();

		final var resource = newResource();
		final var nodeStatusWithData = resource.checkSubscriptionStatus(subscription, null,
				subscriptionResource.getParametersNoCheck(subscription));
		Assertions.assertTrue(nodeStatusWithData.getStatus().isUp());
		final var item = (AzureVm) nodeStatusWithData.getData().get("vm");
		checkItem(item);
		Assertions.assertEquals(VmStatus.POWERED_OFF, item.getStatus());
		Assertions.assertEquals(0, item.getCpu());
		Assertions.assertTrue(item.isBusy());
		Assertions.assertEquals(0, item.getRam());
	}

	@Test
	void checkSubscriptionStatusInvalidSize() throws Exception {
		prepareMockAuth();
		prepareMockNetwork();

		// Find a specific VM
		httpServer
				.stubFor(get(urlPathEqualTo(COMPUTE_URL + "/test1")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
						.withBody(IOUtils.toString(
								new ClassPathResource("mock-server/azure/vm-stoping.json").getInputStream(),
								StandardCharsets.UTF_8))));

		// Expose VM sizes
		httpServer.stubFor(get(urlPathEqualTo(
				"/subscriptions/00000000-0000-0000-0000-000000000000/providers/Microsoft.Compute/locations/westeurope/vmSizes"))
						.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("///DUMMY")));
		httpServer.start();

		final var resource = newResource();
		final var nodeStatusWithData = resource.checkSubscriptionStatus(subscription, null,
				subscriptionResource.getParametersNoCheck(subscription));
		Assertions.assertTrue(nodeStatusWithData.getStatus().isUp());
		final var item = (AzureVm) nodeStatusWithData.getData().get("vm");
		checkItem(item);
		Assertions.assertEquals(VmStatus.POWERED_OFF, item.getStatus());
		Assertions.assertEquals(0, item.getCpu());
		Assertions.assertTrue(item.isBusy());
		Assertions.assertEquals(0, item.getRam());
	}

	private void prepareMockAuth() throws IOException {
		httpServer.stubFor(get(urlPathEqualTo("/11112222-3333-4444-5555-666677778888"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK)
						.withBody(IOUtils.toString(
								new ClassPathResource("mock-server/azure/authentication-oauth.json").getInputStream(),
								StandardCharsets.UTF_8))));
	}

	private void prepareMockVm() throws IOException {
		prepareMockAuth();

		// Find a specific VM
		httpServer
				.stubFor(
						get(urlPathEqualTo(COMPUTE_URL + "/test1")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
								.withBody(IOUtils.toString(
										new ClassPathResource("mock-server/azure/vm-on.json").getInputStream(),
										StandardCharsets.UTF_8))));

		prepareMockNetwork();

		// Expose VM sizes
		httpServer.stubFor(get(urlPathEqualTo(
				"/subscriptions/00000000-0000-0000-0000-000000000000/providers/Microsoft.Compute/locations/westeurope/vmSizes"))
						.willReturn(aResponse().withStatus(HttpStatus.SC_OK)
								.withBody(IOUtils.toString(
										new ClassPathResource("mock-server/azure/list-sizes.json").getInputStream(),
										StandardCharsets.UTF_8))));
		httpServer.start();
	}

	private void prepareMockNetwork() throws IOException {
		// Expose NIC having IP
		httpServer.stubFor(get(urlPathEqualTo(
				"/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/group1/providers/Microsoft.Network/networkInterfaces/test1637"))
						.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils.toString(
								new ClassPathResource("mock-server/azure/vm-nic-with-public.json").getInputStream(),
								StandardCharsets.UTF_8))));
		httpServer.stubFor(get(urlPathEqualTo(
				"/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/group1/providers/Microsoft.Network/publicIPAddresses/vm-0PublicIP"))
						.willReturn(aResponse().withStatus(HttpStatus.SC_OK)
								.withBody(IOUtils.toString(
										new ClassPathResource("mock-server/azure/vm-public-ip.json").getInputStream(),
										StandardCharsets.UTF_8))));
	}

	private void prepareMockFindAll() throws IOException {
		prepareMockAuth();

		// Find a list of VM
		httpServer.stubFor(get(urlPathEqualTo(COMPUTE_URL)).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource("mock-server/azure/find-vm.json").getInputStream(),
						StandardCharsets.UTF_8))));
		httpServer.start();
	}

	@Test
	void checkStatus() throws Exception {
		prepareMockFindAll();
		Assertions.assertTrue(newResource().checkStatus(subscriptionResource.getParametersNoCheck(subscription)));
	}

	private VmAzurePluginResource newResource() throws InterruptedException, ExecutionException, MalformedURLException {
		return newResource(newExecutorService());
	}

	private ExecutorService newExecutorService() {
		final var taskExecutor = Mockito.mock(TaskExecutor.class);
		return new ExecutorServiceAdapter(taskExecutor) {

			@Override
			public void shutdown() {
				// Do nothing
			}
		};

	}

	private VmAzurePluginResource newResource(final ExecutorService service)
			throws InterruptedException, ExecutionException, MalformedURLException {
		var resource = new VmAzurePluginResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		resource = Mockito.spy(resource);
		final var context = Mockito.mock(AuthenticationContext.class);
		@SuppressWarnings("unchecked")
		final Future<AuthenticationResult> future = Mockito.mock(Future.class);
		final var result = new AuthenticationResult("-token-", "-token-", "-token-", 10000, "-token-", null, true);
		Mockito.doReturn(result).when(future).get();
		Mockito.doReturn(future).when(context).acquireToken(ArgumentMatchers.anyString(),
				ArgumentMatchers.any(ClientCredential.class), ArgumentMatchers.any());
		Mockito.doReturn(context).when(resource).newAuthenticationContext("11112222-3333-4444-5555-666677778888",
				service);
		Mockito.doReturn(service).when(resource).newExecutorService();
		return resource;
	}

	private VmAzurePluginResource newResourceFailed() throws MalformedURLException {
		final var service = newExecutorService();
		var resource = new VmAzurePluginResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		resource = Mockito.spy(resource);
		Mockito.doThrow(IllegalStateException.class).when(resource)
				.newAuthenticationContext("11112222-3333-4444-5555-666677778888", service);
		Mockito.doReturn(service).when(resource).newExecutorService();
		return resource;
	}

	/**
	 * Authority does not respond : no defined mock
	 */
	@Test
	void checkStatusAuthorityFailed() {
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> {
			resource.checkStatus(subscriptionResource.getParametersNoCheck(subscription));
		}), AbstractAzureToolPluginResource.PARAMETER_KEY, "azure-login");
	}

	/**
	 * Authority error, client side
	 */
	@Test
	void checkStatusAuthorityError() {
		Assertions.assertThrows(IllegalStateException.class, () -> {
			newResourceFailed().checkStatus(subscriptionResource.getParametersNoCheck(subscription));
		});
	}

	/**
	 * Authority is valid, but the token cannot be acquired
	 */
	@Test
	void checkStatusShudownFailed() throws Exception {
		prepareMockAuth();
		httpServer.start();
		final var taskExecutor = Mockito.mock(TaskExecutor.class);
		final var resource = newResource(new ExecutorServiceAdapter(taskExecutor) {

			@Override
			public void shutdown() {
				throw new IllegalStateException();
			}
		});
		Assertions.assertThrows(IllegalStateException.class, () -> {
			resource.checkStatus(subscriptionResource.getParametersNoCheck(subscription));
		});
	}

	@Test
	void checkStatusNotAccess() throws Exception {
		final var resource = newResource();
		prepareMockAuth();
		httpServer.start();
		MatcherUtil.assertThrows(Assertions.assertThrows(ValidationJsonException.class, () -> {
			resource.checkStatus(subscriptionResource.getParametersNoCheck(subscription));
		}), AbstractAzureToolPluginResource.PARAMETER_SUBSCRIPTION, "azure-admin");
	}

	@Test
	void findAllByName() throws Exception {
		initSpringSecurityContext(DEFAULT_USER);
		prepareMockFindAll();
		final var resource = newResource();
		final var projects = resource.findAllByName("service:vm:azure:test", "est"); // "=test1"
		Assertions.assertEquals(2, projects.size());
		checkItem(projects.get(0));
	}

	@Test
	void findAllByNameNotFound() throws Exception {
		initSpringSecurityContext(DEFAULT_USER);
		prepareMockFindAll();
		final var resource = newResource();
		final var projects = resource.findAllByName("service:vm:azure:test", "any");
		Assertions.assertEquals(0, projects.size());
	}

	@Test
	void findAllByNameNotVisible() throws Exception {
		initSpringSecurityContext("any");
		prepareMockFindAll();
		final var resource = newResource();
		final var projects = resource.findAllByName("service:vm:azure:test", "est"); // "=test1"
		Assertions.assertEquals(0, projects.size());
	}

	@Test
	void execute() throws Exception {
		prepareMockVm();
		final var resource = newResource();
		httpServer.stubFor(post(urlPathEqualTo(COMPUTE_URL + "/test1/powerOff"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK)));

		// Status from ON to OFF
		resource.execute(subscription, VmOperation.OFF);
	}

	/**
	 * Suspend operation is not supported
	 */
	@Test
	void executeSuspend() throws Exception {
		prepareMockVm();

		// Nothing to do
		newResource().execute(subscription, VmOperation.SUSPEND);
	}

	/**
	 * Power Off execution on VM failed.
	 */
	@Test
	void executeFailed() throws Exception {
		// VM is found
		prepareMockVm();

		// But execution, failed : not mocked execution URL
		final var resource = newResource();
		Assertions.assertEquals("vm-operation-execute", Assertions.assertThrows(BusinessException.class, () -> {
			resource.execute(subscription, VmOperation.OFF);
		}).getMessage());
	}

	/**
	 * power ON execution on VM that is already powered ON.
	 */
	@Test
	void executeUselessAction() throws Exception {
		// VM is found : is ON
		prepareMockVm();
		newResource().execute(subscription, VmOperation.ON);
	}

	/**
	 * Dummy bean test to check enforced not null vmSize name.
	 */
	@Test
	void vmSize() {
		Assertions.assertEquals("name", new VmSize("name").getName());
		var size = new VmSize();
		Assertions.assertNull(size.getName());
		size.setName("name");
		Assertions.assertEquals("name", size.getName());
	}

	@Test
	void vmSizeInvalidName() {
		Assertions.assertThrows(NullPointerException.class, () -> {
			new VmSize(null).getClass();
		});
	}

	@Test
	void vmSizeInvalidName2() {
		Assertions.assertThrows(NullPointerException.class, () -> {
			new VmSize().setName(null);
		});
	}

	/**
	 * Check basic VM, not status, not instance details
	 */
	private void checkItem(final AzureVm item) {
		Assertions.assertEquals("vm-id-0", item.getInternalId());
		Assertions.assertEquals("test1", item.getId());
		Assertions.assertEquals("test1", item.getName());
		Assertions.assertEquals("UbuntuServer 16.04-LTS Canonical", item.getOs());
	}

}
