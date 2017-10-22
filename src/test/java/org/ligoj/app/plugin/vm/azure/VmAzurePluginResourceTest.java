package org.ligoj.app.plugin.vm.azure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import javax.transaction.Transactional;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ligoj.app.AbstractServerTest;
import org.ligoj.app.MatcherUtil;
import org.ligoj.app.api.SubscriptionStatusWithData;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Parameter;
import org.ligoj.app.model.ParameterValue;
import org.ligoj.app.model.Project;
import org.ligoj.app.model.Subscription;
import org.ligoj.app.plugin.vm.model.VmOperation;
import org.ligoj.app.plugin.vm.model.VmStatus;
import org.ligoj.app.resource.node.ParameterValueResource;
import org.ligoj.app.resource.subscription.SubscriptionResource;
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
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.microsoft.aad.adal4j.AuthenticationContext;
import com.microsoft.aad.adal4j.AuthenticationResult;
import com.microsoft.aad.adal4j.ClientCredential;

import net.sf.ehcache.CacheManager;

/**
 * Test class of {@link VmAzurePluginResource}
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
public class VmAzurePluginResourceTest extends AbstractServerTest {
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

	@Before
	public void prepareData() throws IOException {
		// Only with Spring context
		persistSystemEntities();
		persistEntities("csv", new Class[] { Node.class, Parameter.class, Project.class, Subscription.class, ParameterValue.class },
				StandardCharsets.UTF_8.name());
		this.subscription = getSubscription("gStack");

		// Coverage only
		resource.getKey();

		configuration.saveOrUpdate("service:vm:azure:management", "http://localhost:" + MOCK_PORT + "/");
		configuration.saveOrUpdate("service:vm:azure:authority", "https://localhost:" + MOCK_PORT + "/");

		// Invalidate azure cache
		CacheManager.getInstance().getCache("curl-tokens").removeAll();
		CacheManager.getInstance().getCache("azure-sizes").removeAll();
	}

	/**
	 * Return the subscription identifier of the given project. Assumes there is
	 * only one subscription for a service.
	 */
	protected int getSubscription(final String project) {
		return getSubscription(project, VmAzurePluginResource.KEY);
	}

	@Test
	public void delete() throws Exception {
		resource.delete(subscription, false);
	}

	@Test
	public void getVersion() throws Exception {
		final String version = resource.getVersion(subscription);
		Assert.assertEquals("2017-03-30", version);
	}

	@Test
	public void link() throws Exception {
		prepareMockVm();

		// Invoke create for an already created entity, since for now, there is
		// nothing but validation pour SonarQube
		final VmAzurePluginResource resource = newResource();
		resource.link(this.subscription);

		// Nothing to validate for now...
	}

	@Test
	public void getVmDetailsNotFound() throws Exception {
		thrown.expect(ValidationJsonException.class);
		thrown.expect(MatcherUtil.validationMatcher(VmAzurePluginResource.PARAMETER_VM, "azure-vm"));
		prepareMockAuth();
		httpServer.start();

		final VmAzurePluginResource resource = newResource();
		final Map<String, String> parameters = pvResource.getNodeParameters("service:vm:azure:test");
		parameters.put(VmAzurePluginResource.PARAMETER_VM, "0");
		resource.getVmDetails(parameters);
	}

	@Test
	public void getVmDetails() throws Exception {
		prepareMockVm();

		final Map<String, String> parameters = pvResource.getNodeParameters("service:vm:azure:test");
		parameters.put(VmAzurePluginResource.PARAMETER_VM, "test1");
		final VmAzurePluginResource resource = newResource();
		final AzureVm vm = resource.getVmDetails(parameters);
		checkItem(vm);
	}

	private void checkVm(final AzureVm item) {
		checkItem(item);
		Assert.assertEquals("westeurope", item.getLocation());
		Assert.assertEquals(VmStatus.POWERED_ON, item.getStatus());
		Assert.assertEquals(30, item.getDisk());
		Assert.assertEquals(1, item.getCpu());
		Assert.assertFalse(item.isBusy());
		Assert.assertEquals(4048, item.getRam());

		// Check network
		Assert.assertEquals(2, item.getNetworks().size());
		Assert.assertEquals("10.0.4.20", item.getNetworks().get(0).getIp());
		Assert.assertEquals("private", item.getNetworks().get(0).getType());
		Assert.assertNull(item.getNetworks().get(0).getDns());
		Assert.assertEquals("1.2.3.4", item.getNetworks().get(1).getIp());
		Assert.assertEquals("public", item.getNetworks().get(1).getType());
		Assert.assertEquals("vm-0-b67589.westeurope.cloudapp.azure.com", item.getNetworks().get(1).getDns());
	}

	@Test
	public void checkSubscriptionStatus() throws Exception {
		prepareMockVm();
		final VmAzurePluginResource resource = newResource();
		final SubscriptionStatusWithData nodeStatusWithData = resource.checkSubscriptionStatus(subscription, null,
				subscriptionResource.getParametersNoCheck(subscription));
		Assert.assertTrue(nodeStatusWithData.getStatus().isUp());
		checkVm((AzureVm) nodeStatusWithData.getData().get("vm"));
	}

	@Test
	public void checkSubscriptionStatusFromImage() throws Exception {
		prepareMockAuth();
		prepareMockNetwork();

		// Find a specific VM
		httpServer.stubFor(get(urlPathEqualTo(COMPUTE_URL + "/test1")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils
				.toString(new ClassPathResource("mock-server/azure/vm-on-from-image.json").getInputStream(), StandardCharsets.UTF_8))));

		// Expose VM sizes
		httpServer.stubFor(get(urlPathEqualTo(
				"/subscriptions/00000000-0000-0000-0000-000000000000/providers/Microsoft.Compute/locations/westeurope/vmSizes"))
						.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils.toString(
								new ClassPathResource("mock-server/azure/list-sizes.json").getInputStream(), StandardCharsets.UTF_8))));
		httpServer.start();

		final VmAzurePluginResource resource = newResource();
		final SubscriptionStatusWithData nodeStatusWithData = resource.checkSubscriptionStatus(subscription, null,
				subscriptionResource.getParametersNoCheck(subscription));
		Assert.assertTrue(nodeStatusWithData.getStatus().isUp());

		final AzureVm vm = (AzureVm) nodeStatusWithData.getData().get("vm");

		Assert.assertEquals("vm-id-0", vm.getInternalId());
		Assert.assertEquals("test1", vm.getId());
		Assert.assertEquals("Linux (debian9-docker17)", vm.getOs());
	}

	@Test(expected = IllegalArgumentException.class)
	public void checkSubscriptionStatusInvalidJson() throws Exception {
		prepareMockAuth();
		prepareMockNetwork();

		// Find a specific VM
		httpServer.stubFor(
				get(urlPathEqualTo(COMPUTE_URL + "/test1")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("<html>")));
		httpServer.start();

		final VmAzurePluginResource resource = newResource();
		resource.checkSubscriptionStatus(subscription, null, subscriptionResource.getParametersNoCheck(subscription));
	}

	@Test
	public void checkSubscriptionStatusNoPublicIp() throws Exception {
		prepareMockAuth();

		// Find a specific VM
		httpServer.stubFor(get(urlPathEqualTo(COMPUTE_URL + "/test1")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/azure/vm-on.json").getInputStream(), StandardCharsets.UTF_8))));

		// Expose VM sizes
		httpServer.stubFor(get(urlPathEqualTo(
				"/subscriptions/00000000-0000-0000-0000-000000000000/providers/Microsoft.Compute/locations/westeurope/vmSizes"))
						.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils.toString(
								new ClassPathResource("mock-server/azure/list-sizes.json").getInputStream(), StandardCharsets.UTF_8))));

		// Only private IP
		httpServer.stubFor(get(urlPathEqualTo(
				"/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/group1/providers/Microsoft.Network/networkInterfaces/test1637"))
						.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils.toString(
								new ClassPathResource("mock-server/azure/vm-nic.json").getInputStream(), StandardCharsets.UTF_8))));

		httpServer.start();

		final VmAzurePluginResource resource = newResource();
		final SubscriptionStatusWithData nodeStatusWithData = resource.checkSubscriptionStatus(subscription, null,
				subscriptionResource.getParametersNoCheck(subscription));
		Assert.assertTrue(nodeStatusWithData.getStatus().isUp());

		final AzureVm vm = (AzureVm) nodeStatusWithData.getData().get("vm");

		Assert.assertEquals("vm-id-0", vm.getInternalId());
		Assert.assertEquals("test1", vm.getId());
		Assert.assertEquals("UbuntuServer 16.04-LTS Canonical", vm.getOs());
		Assert.assertEquals(1, vm.getNetworks().size());
		Assert.assertEquals("10.0.4.20", vm.getNetworks().get(0).getIp());
		Assert.assertNull(vm.getNetworks().get(0).getDns());
	}

	@Test
	public void checkSubscriptionStatusNoSize() throws Exception {
		prepareMockAuth();
		prepareMockNetwork();

		// Find a specific VM
		httpServer.stubFor(get(urlPathEqualTo(COMPUTE_URL + "/test1")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/azure/vm-stoping.json").getInputStream(), StandardCharsets.UTF_8))));

		// Expose VM sizes
		httpServer.stubFor(get(urlPathEqualTo(
				"/subscriptions/00000000-0000-0000-0000-000000000000/providers/Microsoft.Compute/locations/westeurope/vmSizes"))
						.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
								IOUtils.toString(new ClassPathResource("mock-server/azure/list-sizes-empty.json").getInputStream(),
										StandardCharsets.UTF_8))));
		httpServer.start();

		final VmAzurePluginResource resource = newResource();
		final SubscriptionStatusWithData nodeStatusWithData = resource.checkSubscriptionStatus(subscription, null,
				subscriptionResource.getParametersNoCheck(subscription));
		Assert.assertTrue(nodeStatusWithData.getStatus().isUp());
		final AzureVm item = (AzureVm) nodeStatusWithData.getData().get("vm");
		checkItem(item);
		Assert.assertEquals(VmStatus.POWERED_OFF, item.getStatus());
		Assert.assertEquals(0, item.getCpu());
		Assert.assertTrue(item.isBusy());
		Assert.assertEquals(0, item.getRam());
	}

	@Test
	public void checkSubscriptionStatusInvalidSize() throws Exception {
		prepareMockAuth();
		prepareMockNetwork();

		// Find a specific VM
		httpServer.stubFor(get(urlPathEqualTo(COMPUTE_URL + "/test1")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/azure/vm-stoping.json").getInputStream(), StandardCharsets.UTF_8))));

		// Expose VM sizes
		httpServer.stubFor(get(urlPathEqualTo(
				"/subscriptions/00000000-0000-0000-0000-000000000000/providers/Microsoft.Compute/locations/westeurope/vmSizes"))
						.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("///DUMMY")));
		httpServer.start();

		final VmAzurePluginResource resource = newResource();
		final SubscriptionStatusWithData nodeStatusWithData = resource.checkSubscriptionStatus(subscription, null,
				subscriptionResource.getParametersNoCheck(subscription));
		Assert.assertTrue(nodeStatusWithData.getStatus().isUp());
		final AzureVm item = (AzureVm) nodeStatusWithData.getData().get("vm");
		checkItem(item);
		Assert.assertEquals(VmStatus.POWERED_OFF, item.getStatus());
		Assert.assertEquals(0, item.getCpu());
		Assert.assertTrue(item.isBusy());
		Assert.assertEquals(0, item.getRam());
	}

	private void prepareMockAuth() throws IOException {
		httpServer.stubFor(get(urlPathEqualTo("/11112222-3333-4444-5555-666677778888"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils.toString(
						new ClassPathResource("mock-server/azure/authentication-oauth.json").getInputStream(), StandardCharsets.UTF_8))));
	}

	private void prepareMockVm() throws IOException {
		prepareMockAuth();

		// Find a specific VM
		httpServer.stubFor(get(urlPathEqualTo(COMPUTE_URL + "/test1")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/azure/vm-on.json").getInputStream(), StandardCharsets.UTF_8))));

		prepareMockNetwork();

		// Expose VM sizes
		httpServer.stubFor(get(urlPathEqualTo(
				"/subscriptions/00000000-0000-0000-0000-000000000000/providers/Microsoft.Compute/locations/westeurope/vmSizes"))
						.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils.toString(
								new ClassPathResource("mock-server/azure/list-sizes.json").getInputStream(), StandardCharsets.UTF_8))));
		httpServer.start();
	}

	private void prepareMockNetwork() throws IOException {
		// Expose NIC having public IP
		httpServer.stubFor(get(urlPathEqualTo(
				"/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/group1/providers/Microsoft.Network/networkInterfaces/test1637"))
						.willReturn(aResponse().withStatus(HttpStatus.SC_OK)
								.withBody(IOUtils.toString(
										new ClassPathResource("mock-server/azure/vm-nic-with-public.json").getInputStream(),
										StandardCharsets.UTF_8))));
		httpServer.stubFor(get(urlPathEqualTo(
				"/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/group1/providers/Microsoft.Network/publicIPAddresses/vm-0PublicIP"))
						.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils.toString(
								new ClassPathResource("mock-server/azure/vm-public-ip.json").getInputStream(), StandardCharsets.UTF_8))));
	}

	private void prepareMockFindAll() throws IOException {
		prepareMockAuth();

		// Find a list of VM
		httpServer.stubFor(get(urlPathEqualTo(COMPUTE_URL)).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/azure/find-vm.json").getInputStream(), StandardCharsets.UTF_8))));
		httpServer.start();
	}

	@Test
	public void checkStatus() throws Exception {
		prepareMockFindAll();
		Assert.assertTrue(newResource().checkStatus(subscriptionResource.getParametersNoCheck(subscription)));
	}

	private VmAzurePluginResource newResource() throws InterruptedException, ExecutionException, MalformedURLException {
		return newResource(newExecutorService());
	}

	private ExecutorService newExecutorService() {
		final TaskExecutor taskExecutor = Mockito.mock(TaskExecutor.class);
		return new ExecutorServiceAdapter(taskExecutor) {

			@Override
			public void shutdown() {
				// Do nothing
			}
		};

	}

	private VmAzurePluginResource newResource(final ExecutorService service)
			throws InterruptedException, ExecutionException, MalformedURLException {
		VmAzurePluginResource resource = new VmAzurePluginResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		resource = Mockito.spy(resource);
		final AuthenticationContext context = Mockito.mock(AuthenticationContext.class);
		final Future<AuthenticationResult> future = Mockito.mock(Future.class);
		final AuthenticationResult result = new AuthenticationResult("-token-", "-token-", "-token-", 10000, "-token-", null, true);
		Mockito.doReturn(result).when(future).get();
		Mockito.doReturn(future).when(context).acquireToken(ArgumentMatchers.anyString(), ArgumentMatchers.any(ClientCredential.class),
				ArgumentMatchers.any());
		Mockito.doReturn(context).when(resource).newAuthenticationContext("11112222-3333-4444-5555-666677778888", service);
		Mockito.doReturn(service).when(resource).newExecutorService();
		return resource;
	}

	private VmAzurePluginResource newResourceFailed() throws MalformedURLException {
		final ExecutorService service = newExecutorService();
		VmAzurePluginResource resource = new VmAzurePluginResource();
		applicationContext.getAutowireCapableBeanFactory().autowireBean(resource);
		resource = Mockito.spy(resource);
		Mockito.doThrow(IllegalStateException.class).when(resource).newAuthenticationContext("11112222-3333-4444-5555-666677778888",
				service);
		Mockito.doReturn(service).when(resource).newExecutorService();
		return resource;
	}

	/**
	 * Authority does not respond : no defined mock
	 */
	@Test
	public void checkStatusAuthorityFailed() throws Exception {
		thrown.expect(ValidationJsonException.class);
		thrown.expect(MatcherUtil.validationMatcher(VmAzurePluginResource.PARAMETER_KEY, "azure-login"));
		resource.checkStatus(subscriptionResource.getParametersNoCheck(subscription));
	}

	/**
	 * Authority error, client side
	 */
	@Test(expected = IllegalStateException.class)
	public void checkStatusAuthorityError() throws Exception {
		newResourceFailed().checkStatus(subscriptionResource.getParametersNoCheck(subscription));
	}

	/**
	 * Authority is valid, but the token cannot be acquired
	 */
	@Test(expected = IllegalStateException.class)
	public void checkStatusShudownFailed() throws Exception {
		prepareMockAuth();
		httpServer.start();
		final TaskExecutor taskExecutor = Mockito.mock(TaskExecutor.class);
		final VmAzurePluginResource resource = newResource(new ExecutorServiceAdapter(taskExecutor) {

			@Override
			public void shutdown() {
				throw new IllegalStateException();
			}
		});
		resource.checkStatus(subscriptionResource.getParametersNoCheck(subscription));
	}

	@Test
	public void checkStatusNotAccess() throws Exception {
		thrown.expect(ValidationJsonException.class);
		thrown.expect(MatcherUtil.validationMatcher(VmAzurePluginResource.PARAMETER_SUBSCRIPTION, "azure-admin"));
		final VmAzurePluginResource resource = newResource();
		prepareMockAuth();
		httpServer.start();
		resource.checkStatus(subscriptionResource.getParametersNoCheck(subscription));
	}

	@Test
	public void findAllByName() throws Exception {
		initSpringSecurityContext(DEFAULT_USER);
		prepareMockFindAll();
		final VmAzurePluginResource resource = newResource();
		final List<AzureVm> projects = resource.findAllByName("service:vm:azure:test", "est"); // "=test1"
		Assert.assertEquals(2, projects.size());
		checkItem(projects.get(0));
	}

	@Test
	public void findAllByNameNotFound() throws Exception {
		initSpringSecurityContext(DEFAULT_USER);
		prepareMockFindAll();
		final VmAzurePluginResource resource = newResource();
		final List<AzureVm> projects = resource.findAllByName("service:vm:azure:test", "any");
		Assert.assertEquals(0, projects.size());
	}

	@Test
	public void findAllByNameNotVisible() throws Exception {
		initSpringSecurityContext("any");
		prepareMockFindAll();
		final VmAzurePluginResource resource = newResource();
		final List<AzureVm> projects = resource.findAllByName("service:vm:azure:test", "est"); // "=test1"
		Assert.assertEquals(0, projects.size());
	}

	@Test
	public void execute() throws Exception {
		prepareMockVm();
		final VmAzurePluginResource resource = newResource();
		httpServer.stubFor(post(urlPathEqualTo(COMPUTE_URL + "/test1/powerOff")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)));

		// Status from ON to OFF
		resource.execute(subscription, VmOperation.OFF);
	}

	/**
	 * Suspend operation is not supported
	 */
	@Test
	public void executeSuspend() throws Exception {
		prepareMockVm();

		// Nothing to do
		newResource().execute(subscription, VmOperation.SUSPEND);
	}

	/**
	 * Power Off execution on VM failed.
	 */
	@Test(expected = BusinessException.class)
	public void executeFailed() throws Exception {
		// VM is found
		prepareMockVm();

		// But execution, failed : not mocked execution URL
		final VmAzurePluginResource resource = newResource();
		resource.execute(subscription, VmOperation.OFF);
	}

	/**
	 * power ON execution on VM that is already powered ON.
	 */
	@Test
	public void executeUselessAction() throws Exception {
		// VM is found : is ON
		prepareMockVm();
		newResource().execute(subscription, VmOperation.ON);
	}

	/**
	 * Dummy bean test to check enforced not null vmSize name.
	 */
	@Test
	public void vmSize() {
		Assert.assertEquals("name", new VmSize("name").getName());
		VmSize size = new VmSize();
		Assert.assertNull(size.getName());
		size.setName("name");
		Assert.assertEquals("name", size.getName());
	}

	@Test(expected = NullPointerException.class)
	public void vmSizeInvalidName() {
		new VmSize(null);
	}

	@Test(expected = NullPointerException.class)
	public void vmSizeInvalidName2() {
		new VmSize().setName(null);
	}

	/**
	 * Check basic VM, not status, not instance details
	 */
	private void checkItem(final AzureVm item) {
		Assert.assertEquals("vm-id-0", item.getInternalId());
		Assert.assertEquals("test1", item.getId());
		Assert.assertEquals("test1", item.getName());
		Assert.assertEquals("UbuntuServer 16.04-LTS Canonical", item.getOs());
	}

}
