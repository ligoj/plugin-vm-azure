package org.ligoj.app.plugin.vm.azure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import javax.transaction.Transactional;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
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
import org.ligoj.app.plugin.vm.azure.AzurePluginResource;
import org.ligoj.app.plugin.vm.azure.Vm;
import org.ligoj.app.plugin.vm.model.VmOperation;
import org.ligoj.app.plugin.vm.model.VmStatus;
import org.ligoj.app.resource.node.NodeResource;
import org.ligoj.app.resource.subscription.SubscriptionResource;
import org.ligoj.bootstrap.core.IDescribableBean;
import org.ligoj.bootstrap.core.resource.BusinessException;
import org.ligoj.bootstrap.core.validation.ValidationJsonException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.github.tomakehurst.wiremock.stubbing.Scenario;

import net.sf.ehcache.CacheManager;

/**
 * Test class of {@link AzurePluginResource}
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
public class AzurePluginResourceTest extends AbstractServerTest {
	@Autowired
	private AzurePluginResource resource;

	@Autowired
	private NodeResource nodeResource;

	@Autowired
	private SubscriptionResource subscriptionResource;

	protected int subscription;

	@Before
	public void prepareData() throws IOException {
		// Only with Spring context
		persistEntities("csv", new Class[] { Node.class, Parameter.class, Project.class, Subscription.class, ParameterValue.class },
				StandardCharsets.UTF_8.name());
		this.subscription = getSubscription("gStack");

		// Coverage only
		resource.getKey();

		// Invalidate vCloud cache
		CacheManager.getInstance().getCache("curl-tokens").removeAll();
	}

	/**
	 * Return the subscription identifier of the given project. Assumes there is only one subscription for a service.
	 */
	protected int getSubscription(final String project) {
		return getSubscription(project, AzurePluginResource.KEY);
	}

	@Test
	public void delete() throws Exception {
		resource.delete(subscription, false);
	}

	@Test
	public void getVersion() throws Exception {
		prepareMockVersion();

		final String version = resource.getVersion(subscription);
		Assert.assertEquals("5.5.4.2831206 Fri Jun 19 15:07:32 CEST 2015", version);
	}

	@Test
	public void getLastVersion() throws Exception {
		final String lastVersion = resource.getLastVersion();
		Assert.assertNotNull(lastVersion);
		Assert.assertTrue(lastVersion.compareTo("6.0") >= 0);
	}

	@Test
	public void link() throws Exception {
		prepareMockItem();
		httpServer.start();

		// Invoke create for an already created entity, since for now, there is nothing but validation pour SonarQube
		resource.link(this.subscription);

		// Nothing to validate for now...
	}

	@Test
	public void validateVmNotFound() throws Exception {
		thrown.expect(ValidationJsonException.class);
		thrown.expect(MatcherUtil.validationMatcher(AzurePluginResource.PARAMETER_VM, "vcloud-vm"));
		prepareMockHome();

		// Not find VM
		httpServer.stubFor(get(urlPathEqualTo("/query")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("<a/>")));
		httpServer.start();

		final Map<String, String> parameters = nodeResource.getParametersAsMap("service:vm:vcloud:obs-fca-info");
		parameters.put(AzurePluginResource.PARAMETER_VM, "0");
		resource.validateVm(parameters);
	}

	@Test
	public void validateVm() throws Exception {
		prepareMockItem();

		final Map<String, String> parameters = nodeResource.getParametersAsMap("service:vm:vcloud:obs-fca-info");
		parameters.put(AzurePluginResource.PARAMETER_VM, "75aa69b4-8cff-40cd-9338-9abafc7d5935");
		final Vm vm = resource.validateVm(parameters);
		checkVm(vm);
		Assert.assertTrue(vm.isDeployed());
	}

	private void checkVm(final Vm item) {
		checkItem(item);
		Assert.assertEquals("High Performances", item.getStorageProfileName());
		Assert.assertEquals(VmStatus.POWERED_OFF, item.getStatus());
		Assert.assertEquals(6, item.getNumberOfCpus());
		Assert.assertFalse(item.isBusy());
		Assert.assertEquals("vApp_BPR", item.getContainerName());
		Assert.assertEquals(28672, item.getMemoryMB());
	}

	@Test
	public void checkSubscriptionStatus() throws Exception {
		prepareMockItem();
		final SubscriptionStatusWithData nodeStatusWithData = resource
				.checkSubscriptionStatus(subscriptionResource.getParametersNoCheck(subscription));
		Assert.assertTrue(nodeStatusWithData.getStatus().isUp());
		checkVm((Vm) nodeStatusWithData.getData().get("vm"));
	}

	private void prepareMockItem() throws IOException {
		prepareMockHome();

		// Find a specific VM
		httpServer.stubFor(get(urlPathEqualTo("/query")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils.toString(
				new ClassPathResource("mock-server/vcloud/vcloud-query-vm-poweredoff-deployed.xml").getInputStream(), StandardCharsets.UTF_8))));
		httpServer.start();
	}

	private void prepareMockFindAll() throws IOException {
		prepareMockHome();

		// Find a list of VM
		httpServer.stubFor(get(urlPathEqualTo("/query")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
				IOUtils.toString(new ClassPathResource("mock-server/vcloud/vcloud-query-search.xml").getInputStream(), StandardCharsets.UTF_8))));
		httpServer.start();
	}

	@Test
	public void checkStatus() throws Exception {
		prepareMockVersion();
		Assert.assertTrue(resource.checkStatus(subscriptionResource.getParametersNoCheck(subscription)));
	}

	@Test
	public void checkStatusAuthenticationFailed() throws Exception {
		thrown.expect(ValidationJsonException.class);
		thrown.expect(MatcherUtil.validationMatcher(AzurePluginResource.PARAMETER_URL, "vcloud-login"));
		httpServer.stubFor(post(urlPathEqualTo("/sessions")).willReturn(aResponse().withStatus(HttpStatus.SC_FORBIDDEN)));
		httpServer.start();
		resource.checkStatus(subscriptionResource.getParametersNoCheck(subscription));
	}

	@Test
	public void checkStatusAuthenticationFailedThenSucceed() throws Exception {
		prepareMockVersion();
		httpServer.stubFor(post(urlPathEqualTo("/sessions")).inScenario("auth").whenScenarioStateIs(Scenario.STARTED)
				.willReturn(aResponse().withStatus(HttpStatus.SC_FORBIDDEN)).willSetStateTo("failed"));
		httpServer.stubFor(post(urlPathEqualTo("/sessions")).inScenario("auth").whenScenarioStateIs("failed")
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withHeader("x-vcloud-authorization", "token")));
		httpServer.stubFor(get(urlPathEqualTo("/admin")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource("mock-server/vcloud/vcloud-admin.xml").getInputStream(), StandardCharsets.UTF_8))));
		Assert.assertTrue(resource.checkStatus(subscriptionResource.getParametersNoCheck(subscription)));
		httpServer.start();
		resource.checkStatus(subscriptionResource.getParametersNoCheck(subscription));
	}

	@Test
	public void checkStatusNotAdmin() throws Exception {
		thrown.expect(ValidationJsonException.class);
		thrown.expect(MatcherUtil.validationMatcher(AzurePluginResource.PARAMETER_URL, "vcloud-admin"));
		prepareMockHome();
		httpServer.start();
		resource.checkStatus(subscriptionResource.getParametersNoCheck(subscription));
	}

	@Test
	public void checkStatusNotAccess() throws Exception {
		thrown.expect(ValidationJsonException.class);
		thrown.expect(MatcherUtil.validationMatcher(AzurePluginResource.PARAMETER_URL, "vcloud-admin"));
		httpServer.stubFor(
				post(urlPathEqualTo("/sessions")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withHeader("x-vcloud-authorization", "token")));
		httpServer.start();
		resource.checkStatus(subscriptionResource.getParametersNoCheck(subscription));
	}

	private void prepareMockVersion() throws IOException {
		prepareMockHome();

		// Version from "/admin"
		httpServer.stubFor(get(urlPathEqualTo("/admin")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)
				.withBody(IOUtils.toString(new ClassPathResource("mock-server/vcloud/vcloud-admin.xml").getInputStream(), StandardCharsets.UTF_8))));
		httpServer.start();
	}

	private void prepareMockHome() {
		httpServer.stubFor(
				post(urlPathEqualTo("/sessions")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withHeader("x-vcloud-authorization", "token")));
	}

	@Test
	public void findAllByName() throws Exception {
		prepareMockFindAll();
		httpServer.start();

		final List<Vm> projects = resource.findAllByName("service:vm:vcloud:obs-fca-info", "sc");
		Assert.assertEquals(3, projects.size());
		checkItem(projects.get(0));
	}

	@Test
	public void getConsole() throws Exception {
		prepareMockHome();
		httpServer.stubFor(get(urlPathEqualTo("/vApp/vm-75aa69b4-8cff-40cd-9338-9abafc7d5935/screen"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(
						IOUtils.toString(new ClassPathResource("mock-server/vcloud/vcloud-console.png").getInputStream(), StandardCharsets.UTF_8))));
		httpServer.start();

		final StreamingOutput imageStream = resource.getConsole(subscription);
		final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		imageStream.write(outputStream);
		Assert.assertTrue(outputStream.toByteArray().length > 1024);
	}

	@Test
	public void getConsoleNotAvailable() throws Exception {
		prepareMockHome();
		httpServer.stubFor(
				get(urlPathEqualTo("/vApp/vm-75aa69b4-8cff-40cd-9338-9abafc7d5935/screen")).willReturn(aResponse().withStatus(HttpStatus.SC_OK)));
		httpServer.start();

		final StreamingOutput imageStream = resource.getConsole(subscription);
		final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		imageStream.write(outputStream);
		Assert.assertEquals(0, outputStream.toByteArray().length);
	}

	@Test
	public void getConsoleError() throws Exception {
		prepareMockHome();
		httpServer.stubFor(get(urlPathEqualTo("/vApp/vm-75aa69b4-8cff-40cd-9338-9abafc7d5935/screen"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_NO_CONTENT)));
		httpServer.start();

		final StreamingOutput imageStream = resource.getConsole(subscription);
		final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		imageStream.write(outputStream);
		Assert.assertEquals(0, outputStream.toByteArray().length);
	}

	@Test
	public void execute() throws Exception {
		httpServer.stubFor(post(urlPathEqualTo("/vApp/vm-75aa69b4-8cff-40cd-9338-9abafc7d5935/power/action/powerOn"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("<Task>...</Task>")));
		prepareMockItem();
		resource.execute(subscription, VmOperation.ON);
	}

	/**
	 * Shutdown execution requires an undeploy action.
	 */
	@Test
	public void executeShutDown() throws Exception {
		prepareMockHome();

		// Find a specific VM
		httpServer.stubFor(get(urlPathEqualTo("/query")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils
				.toString(new ClassPathResource("mock-server/vcloud/vcloud-query-vm-poweredon.xml").getInputStream(), StandardCharsets.UTF_8))));

		// Stub the undeploy action
		httpServer.stubFor(post(urlPathEqualTo("/vApp/vm-75aa69b4-8cff-40cd-9338-9abafc7d5935/action/undeploy"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("<Task>...</Task>")));
		httpServer.start();
		resource.execute(subscription, VmOperation.SHUTDOWN);
	}

	/**
	 * Power Off execution requires an undeploy action.
	 */
	@Test
	public void executeOff() throws Exception {
		prepareMockHome();

		// Find a specific VM
		httpServer.stubFor(get(urlPathEqualTo("/query")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils
				.toString(new ClassPathResource("mock-server/vcloud/vcloud-query-vm-poweredon.xml").getInputStream(), StandardCharsets.UTF_8))));

		// Stub the undeploy action
		httpServer.stubFor(post(urlPathEqualTo("/vApp/vm-75aa69b4-8cff-40cd-9338-9abafc7d5935/action/undeploy"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody("<Task>...</Task>")));
		httpServer.start();
		resource.execute(subscription, VmOperation.OFF);
	}

	/**
	 * Power Off execution requires an undeploy action.
	 */
	@Test(expected = BusinessException.class)
	public void executeInvalidAction() throws Exception {
		prepareMockHome();

		// Find a specific VM
		httpServer.stubFor(get(urlPathEqualTo("/query")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils
				.toString(new ClassPathResource("mock-server/vcloud/vcloud-query-vm-poweredon.xml").getInputStream(), StandardCharsets.UTF_8))));

		// Stub the undeploy action
		httpServer.stubFor(post(urlPathEqualTo("/vApp/vm-75aa69b4-8cff-40cd-9338-9abafc7d5935/action/undeploy"))
				.willReturn(aResponse().withStatus(HttpStatus.SC_BAD_REQUEST).withBody("<Error>...</Error>")));
		httpServer.start();
		resource.execute(subscription, VmOperation.OFF);
	}

	/**
	 * Shutdown execution on VM that is already powered off.
	 */
	@Test
	public void executeUselessAction() throws Exception {
		prepareMockHome();

		// Find a specific VM
		httpServer.stubFor(get(urlPathEqualTo("/query")).willReturn(aResponse().withStatus(HttpStatus.SC_OK).withBody(IOUtils
				.toString(new ClassPathResource("mock-server/vcloud/vcloud-query-vm-poweredon.xml").getInputStream(), StandardCharsets.UTF_8))));
		httpServer.start();
		resource.execute(subscription, VmOperation.ON);
	}

	private void checkItem(final IDescribableBean<String> item) {
		Assert.assertEquals("75aa69b4-8cff-40cd-9338-9abafc7d5935", item.getId());
		Assert.assertEquals("sca", item.getName());
		Assert.assertEquals("CentOS 4/5/6/7 (64-bit)", item.getDescription());
	}

}
