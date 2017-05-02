package de.zalando.ep.zalenium.proxy;

import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.LogStream;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.messages.*;
import de.zalando.ep.zalenium.util.Environment;
import de.zalando.ep.zalenium.util.TestUtils;
import org.awaitility.Duration;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.internal.Registry;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.web.servlet.handler.RequestType;
import org.openqa.grid.web.servlet.handler.WebDriverRequest;
import org.openqa.selenium.Platform;
import org.openqa.selenium.remote.BrowserType;
import org.openqa.selenium.remote.CapabilityType;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class DockerSeleniumRemoteProxyTest {

    private DockerSeleniumRemoteProxy proxy;
    private Registry registry;

    @Before
    public void setUp() throws DockerException, InterruptedException, IOException {
        registry = Registry.newInstance();

        // Creating the configuration and the registration request of the proxy (node)
        RegistrationRequest request = TestUtils.getRegistrationRequestForTesting(40000,
                DockerSeleniumRemoteProxy.class.getCanonicalName());
        request.getConfiguration().capabilities.clear();
        request.getConfiguration().capabilities.addAll(TestUtils.getDockerSeleniumCapabilitiesForTesting());

        // Creating the proxy
        proxy = DockerSeleniumRemoteProxy.getNewInstance(request, registry);

        DockerClient dockerClient = mock(DockerClient.class);
        ExecCreation execCreation = mock(ExecCreation.class);
        LogStream logStream = mock(LogStream.class);
        when(logStream.readFully()).thenReturn("ANY_STRING");
        when(execCreation.id()).thenReturn("ANY_ID");
        when(dockerClient.execCreate(anyString(), any(String[].class), any(DockerClient.ExecCreateParam.class),
                any(DockerClient.ExecCreateParam.class))).thenReturn(execCreation);
        when(dockerClient.execStart(anyString())).thenReturn(logStream);
        doNothing().when(dockerClient).stopContainer(anyString(), anyInt());

        DockerSeleniumRemoteProxy.setDockerClient(dockerClient);
    }

    @After
    public void tearDown() {
        DockerSeleniumRemoteProxy.restoreDockerClient();
        DockerSeleniumRemoteProxy.restoreEnvironment();
    }

    @Test
    public void dockerSeleniumOnlyRunsOneTestPerContainer() {
        // Supported desired capability for the test session
        Map<String, Object> requestedCapability = getCapabilitySupportedByDockerSelenium();

        // Not tests have been executed.
        Assert.assertEquals(0, proxy.getAmountOfExecutedTests());

        TestSession newSession = proxy.getNewSession(requestedCapability);

        Assert.assertNotNull(newSession);

        // One test is/has been executed and the session amount limit was reached.
        Assert.assertEquals(1, proxy.getAmountOfExecutedTests());
        Assert.assertTrue(proxy.isTestSessionLimitReached());
    }

    @Test
    public void secondRequestGetsANullTestRequest() {
        // Supported desired capability for the test session
        Map<String, Object> requestedCapability = getCapabilitySupportedByDockerSelenium();
        requestedCapability.put("name", "anyRandomTestName");
        requestedCapability.put("group", "anyRandomTestGroup");

        {
            TestSession newSession = proxy.getNewSession(requestedCapability);
            Assert.assertNotNull(newSession);
        }

        // Since only one test should be executed, the second request should come null
        {
            TestSession newSession = proxy.getNewSession(requestedCapability);
            Assert.assertNull(newSession);
        }

        Assert.assertEquals("anyRandomTestGroup", proxy.getTestGroup());
        Assert.assertEquals("anyRandomTestName", proxy.getTestName());
    }

    @Test
    public void noSessionIsCreatedWhenCapabilitiesAreNotSupported() {
        // Non supported capabilities
        Map<String, Object> requestedCapability = new HashMap<>();
        requestedCapability.put(CapabilityType.BROWSER_NAME, BrowserType.IE);
        requestedCapability.put(CapabilityType.PLATFORM, Platform.WIN10);

        TestSession newSession = proxy.getNewSession(requestedCapability);
        Assert.assertNull(newSession);
    }

    @Test
    public void testIdleTimeoutUsesDefaultValueWhenCapabilityIsNotPresent() {
        Map<String, Object> requestedCapability = getCapabilitySupportedByDockerSelenium();

        TestSession newSession = proxy.getNewSession(requestedCapability);
        Assert.assertNotNull(newSession);
        Assert.assertEquals(proxy.getMaxTestIdleTimeSecs(), DockerSeleniumRemoteProxy.DEFAULT_MAX_TEST_IDLE_TIME_SECS);
    }

    @Test
    public void testIdleTimeoutUsesDefaultValueWhenCapabilityHasNegativeValue() {
        Map<String, Object> requestedCapability = getCapabilitySupportedByDockerSelenium();
        requestedCapability.put("idleTimeout", -20L);

        proxy.getNewSession(requestedCapability);
        Assert.assertEquals(proxy.getMaxTestIdleTimeSecs(), DockerSeleniumRemoteProxy.DEFAULT_MAX_TEST_IDLE_TIME_SECS);
    }

    @Test
    public void testIdleTimeoutUsesDefaultValueWhenCapabilityHasFaultyValue() {
        Map<String, Object> requestedCapability = getCapabilitySupportedByDockerSelenium();
        requestedCapability.put("idleTimeout", "thisValueIsNAN Should not work.");

        proxy.getNewSession(requestedCapability);
        Assert.assertEquals(proxy.getMaxTestIdleTimeSecs(), DockerSeleniumRemoteProxy.DEFAULT_MAX_TEST_IDLE_TIME_SECS);
    }

    @Test
    public void testIdleTimeoutUsesValuePassedAsCapability() {
        Map<String, Object> requestedCapability = getCapabilitySupportedByDockerSelenium();
        requestedCapability.put("idleTimeout", 180L);

        TestSession newSession = proxy.getNewSession(requestedCapability);
        Assert.assertNotNull(newSession);
        Assert.assertEquals(proxy.getMaxTestIdleTimeSecs(), 180L);
    }

    @Test
    public void pollerThreadTearsDownNodeAfterTestIsCompleted() throws IOException {

        // Supported desired capability for the test session
        Map<String, Object> requestedCapability = getCapabilitySupportedByDockerSelenium();

        // Start poller thread
        proxy.startPolling();

        // Get a test session
        TestSession newSession = proxy.getNewSession(requestedCapability);
        Assert.assertNotNull(newSession);

        // The node should be busy since there is a session in it
        Assert.assertTrue(proxy.isBusy());

        // We release the session, the node should be free
        WebDriverRequest request = mock(WebDriverRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getMethod()).thenReturn("DELETE");
        when(request.getRequestType()).thenReturn(RequestType.STOP_SESSION);

        newSession.getSlot().doFinishRelease();
        proxy.afterCommand(newSession, request, response);
        proxy.afterSession(newSession);

        // After running one test, the node shouldn't be busy and also down
        Assert.assertFalse(proxy.isBusy());
        Callable<Boolean> callable = () -> proxy.isDown();
        await().pollInterval(Duration.FIVE_HUNDRED_MILLISECONDS).atMost(Duration.TWO_SECONDS).until(callable);
    }

    @Test
    public void normalSessionCommandsDoNotStopNode() throws IOException {

        // Supported desired capability for the test session
        Map<String, Object> requestedCapability = getCapabilitySupportedByDockerSelenium();

        // Start poller thread
        proxy.startPolling();

        // Get a test session
        TestSession newSession = proxy.getNewSession(requestedCapability);
        Assert.assertNotNull(newSession);

        // The node should be busy since there is a session in it
        Assert.assertTrue(proxy.isBusy());

        // We release the session, the node should be free
        WebDriverRequest request = mock(WebDriverRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestType()).thenReturn(RequestType.REGULAR);

        proxy.afterCommand(newSession, request, response);

        // The node should not tear down
        Assert.assertTrue(proxy.isBusy());
        Callable<Boolean> callable = () -> !proxy.isDown();
        await().pollInterval(Duration.FIVE_HUNDRED_MILLISECONDS).atMost(Duration.TWO_SECONDS).until(callable);
    }

    @Test
    public void nodeShutsDownWhenTestIsIdle() throws IOException {

        // Supported desired capability for the test session
        Map<String, Object> requestedCapability = getCapabilitySupportedByDockerSelenium();
        requestedCapability.put("idleTimeout", 1L);

        DockerSeleniumRemoteProxy spyProxy = spy(proxy);

        // Start poller thread
        spyProxy.startPolling();

        // Get a test session
        TestSession newSession = spyProxy.getNewSession(requestedCapability);
        Assert.assertNotNull(newSession);

        // Start the session
        WebDriverRequest webDriverRequest = mock(WebDriverRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(webDriverRequest.getMethod()).thenReturn("POST");
        when(webDriverRequest.getRequestType()).thenReturn(RequestType.START_SESSION);
        spyProxy.beforeCommand(newSession, webDriverRequest, response);

        // The node should be busy since there is a session in it
        Assert.assertTrue(spyProxy.isBusy());

        // The node should tear down after the maximum idle time is elapsed
        Assert.assertTrue(spyProxy.isBusy());
        Callable<Boolean> callable = spyProxy::isDown;
        await().pollInterval(Duration.FIVE_HUNDRED_MILLISECONDS).atMost(Duration.FIVE_SECONDS).until(callable);
    }

    @Test
    public void fallbackToDefaultValueWhenEnvVariableIsNotABoolean() {
        Environment environment = mock(Environment.class, withSettings().useConstructor());
        when(environment.getEnvVariable(DockerSeleniumRemoteProxy.ZALENIUM_VIDEO_RECORDING_ENABLED))
                .thenReturn("any_nonsense_value");
        when(environment.getBooleanEnvVariable(any(String.class), any(Boolean.class))).thenCallRealMethod();
        DockerSeleniumRemoteProxy.setEnv(environment);
        DockerSeleniumRemoteProxy.readEnvVarForVideoRecording();

        Assert.assertEquals(DockerSeleniumRemoteProxy.DEFAULT_VIDEO_RECORDING_ENABLED,
                DockerSeleniumRemoteProxy.isVideoRecordingEnabled());
    }

    @Test
    public void videoRecordingIsStartedAndStopped() throws DockerException, InterruptedException,
            URISyntaxException, IOException {

        DockerClient dockerClient = new DefaultDockerClient("unix:///var/run/docker.sock");
        String containerId = null;
        String zaleniumContainerId = null;
        try {
            cleanUpZaleniumContainer(dockerClient);

            // We create a container with the name "zalenium", so the container creation in the next step works
            zaleniumContainerId = createZaleniumContainer(dockerClient);

            // Create a docker-selenium container
            RegistrationRequest request = TestUtils.getRegistrationRequestForTesting(30000,
                    DockerSeleniumStarterRemoteProxy.class.getCanonicalName());
            DockerSeleniumStarterRemoteProxy dsProxy = new DockerSeleniumStarterRemoteProxy(request, registry);
            DockerSeleniumStarterRemoteProxy.setMaxDockerSeleniumContainers(1);
            DockerSeleniumStarterRemoteProxy.setScreenHeight(DockerSeleniumStarterRemoteProxy.DEFAULT_SCREEN_HEIGHT);
            DockerSeleniumStarterRemoteProxy.setScreenWidth(DockerSeleniumStarterRemoteProxy.DEFAULT_SCREEN_WIDTH);
            DockerSeleniumStarterRemoteProxy.setTimeZone(DockerSeleniumStarterRemoteProxy.DEFAULT_TZ);
            dsProxy.getNewSession(getCapabilitySupportedByDockerSelenium());

            // Creating a spy proxy to verify the invoked methods
            DockerSeleniumRemoteProxy spyProxy = spy(proxy);
            DockerSeleniumRemoteProxy.setDockerClient(dockerClient);

            // Wait for the container to be ready
            containerId = waitForContainerToBeReady(dockerClient, spyProxy);

            // Start poller thread
            spyProxy.startPolling();

            // Get a test session
            TestSession newSession = spyProxy.getNewSession(getCapabilitySupportedByDockerSelenium());
            Assert.assertNotNull(newSession);

            // We start the session, in order to start recording
            WebDriverRequest webDriverRequest = mock(WebDriverRequest.class);
            HttpServletResponse response = mock(HttpServletResponse.class);
            when(webDriverRequest.getMethod()).thenReturn("POST");
            when(webDriverRequest.getRequestType()).thenReturn(RequestType.START_SESSION);
            spyProxy.beforeCommand(newSession, webDriverRequest, response);

            // Assert video recording started
            verify(spyProxy, times(1)).
                    videoRecording(DockerSeleniumRemoteProxy.DockerSeleniumContainerAction.START_RECORDING);
            verify(spyProxy, times(1)).
                    processContainerAction(DockerSeleniumRemoteProxy.DockerSeleniumContainerAction.START_RECORDING, containerId);

            // We release the sessions, the node should be free
            webDriverRequest = mock(WebDriverRequest.class);
            response = mock(HttpServletResponse.class);
            when(webDriverRequest.getMethod()).thenReturn("DELETE");
            when(webDriverRequest.getRequestType()).thenReturn(RequestType.STOP_SESSION);

            newSession.getSlot().doFinishRelease();
            spyProxy.afterCommand(newSession, webDriverRequest, response);
            spyProxy.afterSession(newSession);

            Assert.assertFalse(spyProxy.isBusy());
            verify(spyProxy, timeout(40000))
                    .videoRecording(DockerSeleniumRemoteProxy.DockerSeleniumContainerAction.STOP_RECORDING);
            verify(spyProxy, timeout(40000))
                    .processContainerAction(DockerSeleniumRemoteProxy.DockerSeleniumContainerAction.STOP_RECORDING, containerId);
            verify(spyProxy, timeout(40000)).copyVideos(containerId);
        } finally {
            cleanUpAfterVideoRecordingTests(dockerClient, containerId, zaleniumContainerId);
        }
    }

    @Test
    public void videoRecordingIsDisabled() throws DockerException, InterruptedException, IOException, URISyntaxException {

        DockerClient dockerClient = new DefaultDockerClient("unix:///var/run/docker.sock");
        String containerId = null;
        String zaleniumContainerId = null;
        try {
            cleanUpZaleniumContainer(dockerClient);

            // We create a container with the name "zalenium", so the container creation in the next step works
            zaleniumContainerId = createZaleniumContainer(dockerClient);

            // Create a docker-selenium container
            RegistrationRequest request = TestUtils.getRegistrationRequestForTesting(30000,
                    DockerSeleniumStarterRemoteProxy.class.getCanonicalName());
            DockerSeleniumStarterRemoteProxy dsProxy = new DockerSeleniumStarterRemoteProxy(request, registry);
            DockerSeleniumStarterRemoteProxy.setMaxDockerSeleniumContainers(1);
            DockerSeleniumStarterRemoteProxy.setScreenHeight(DockerSeleniumStarterRemoteProxy.DEFAULT_SCREEN_HEIGHT);
            DockerSeleniumStarterRemoteProxy.setScreenWidth(DockerSeleniumStarterRemoteProxy.DEFAULT_SCREEN_WIDTH);
            DockerSeleniumStarterRemoteProxy.setTimeZone(DockerSeleniumStarterRemoteProxy.DEFAULT_TZ);
            DockerSeleniumStarterRemoteProxy.setConfiguredTimeZone(DockerSeleniumStarterRemoteProxy.DEFAULT_TZ);
            dsProxy.getNewSession(getCapabilitySupportedByDockerSelenium());

            // Mocking the environment variable to return false for video recording enabled
            Environment environment = mock(Environment.class);
            when(environment.getEnvVariable(DockerSeleniumRemoteProxy.ZALENIUM_VIDEO_RECORDING_ENABLED))
                    .thenReturn("false");

            // Creating a spy proxy to verify the invoked methods
            DockerSeleniumRemoteProxy spyProxy = spy(proxy);
            DockerSeleniumRemoteProxy.setDockerClient(dockerClient);
            DockerSeleniumRemoteProxy.setEnv(environment);
            DockerSeleniumRemoteProxy.readEnvVarForVideoRecording();

            // Wait for the container to be ready
            containerId = waitForContainerToBeReady(dockerClient, spyProxy);

            // Start poller thread
            spyProxy.startPolling();

            // Get a test session
            TestSession newSession = spyProxy.getNewSession(getCapabilitySupportedByDockerSelenium());
            Assert.assertNotNull(newSession);

            // We start the session, in order to start recording
            WebDriverRequest webDriverRequest = mock(WebDriverRequest.class);
            HttpServletResponse response = mock(HttpServletResponse.class);
            when(webDriverRequest.getMethod()).thenReturn("POST");
            when(webDriverRequest.getRequestType()).thenReturn(RequestType.START_SESSION);
            spyProxy.beforeCommand(newSession, webDriverRequest, response);

            // Assert no video recording was started, videoRecording is invoked but processContainerAction should not
            verify(spyProxy, times(1))
                    .videoRecording(DockerSeleniumRemoteProxy.DockerSeleniumContainerAction.START_RECORDING);
            verify(spyProxy, never())
                    .processContainerAction(DockerSeleniumRemoteProxy.DockerSeleniumContainerAction.START_RECORDING, containerId);

            // We release the sessions, the node should be free
            webDriverRequest = mock(WebDriverRequest.class);
            response = mock(HttpServletResponse.class);
            when(webDriverRequest.getMethod()).thenReturn("DELETE");
            when(webDriverRequest.getRequestType()).thenReturn(RequestType.STOP_SESSION);

            newSession.getSlot().doFinishRelease();
            spyProxy.afterCommand(newSession, webDriverRequest, response);
            spyProxy.afterSession(newSession);

            Assert.assertFalse(spyProxy.isBusy());
            // Now we assert that videoRecording was invoked but processContainerAction not, neither copyVideos
            verify(spyProxy, timeout(40000))
                    .videoRecording(DockerSeleniumRemoteProxy.DockerSeleniumContainerAction.STOP_RECORDING);
            verify(spyProxy, never())
                    .processContainerAction(DockerSeleniumRemoteProxy.DockerSeleniumContainerAction.STOP_RECORDING, containerId);
            verify(spyProxy, never()).copyVideos(containerId);
        } finally {
            cleanUpAfterVideoRecordingTests(dockerClient, containerId, zaleniumContainerId);
        }
    }

    @Test
    public void videoRecordingIsDisabledViaCapability() {
        Map<String, Object> requestedCapability = getCapabilitySupportedByDockerSelenium();
        requestedCapability.put("recordVideo", false);

        TestSession newSession = proxy.getNewSession(requestedCapability);
        Assert.assertNotNull(newSession);
        Assert.assertEquals(DockerSeleniumRemoteProxy.isVideoRecordingEnabled(), false);
    }

    private void cleanUpAfterVideoRecordingTests(DockerClient dockerClient, String containerId,
                                     String zaleniumContainerId) throws DockerException, InterruptedException {
        String busyboxLatestImage = "busybox:latest";
        DockerSeleniumStarterRemoteProxy.setMaxDockerSeleniumContainers(0);
        if (containerId != null) {
            dockerClient.stopContainer(containerId, 5);
        }
        if (zaleniumContainerId != null) {
            dockerClient.stopContainer(zaleniumContainerId, 5);
        }
        dockerClient.removeImage(busyboxLatestImage, true, true);
    }

    private String waitForContainerToBeReady(DockerClient dockerClient, DockerSeleniumRemoteProxy spyProxy)
            throws DockerException, InterruptedException {
        Callable<Boolean> callable = () -> spyProxy.getContainerId() != null;
        await().atMost(20, SECONDS).pollInterval(500, MILLISECONDS).until(callable);
        String containerId = spyProxy.getContainerId();
        final String[] command = {"bash", "-c", "wait_all_done 30s"};
        final ExecCreation execCreation = dockerClient.execCreate(containerId, command,
                DockerClient.ExecCreateParam.attachStdout(), DockerClient.ExecCreateParam.attachStderr());
        dockerClient.execStart(execCreation.id());

        final String finalContainerId = containerId;
        callable = () ->
                !dockerClient.topContainer(finalContainerId).processes().toString().contains("wait_all_done");
        await().atMost(40, SECONDS).pollInterval(2, SECONDS).until(callable);
        return containerId;
    }

    private String createZaleniumContainer(DockerClient dockerClient) throws DockerException, InterruptedException {
        String busyboxLatestImage = "busybox:latest";
        dockerClient.pull(busyboxLatestImage);
        final HostConfig hostConfig = HostConfig.builder()
                .autoRemove(true)
                .build();
        final ContainerConfig containerConfig = ContainerConfig.builder()
                .image(busyboxLatestImage)
                // make sure the container's busy doing something upon startup
                .cmd("sh", "-c", "while :; do sleep 1; done")
                .hostConfig(hostConfig)
                .build();
        final ContainerCreation containerCreation = dockerClient.createContainer(containerConfig, "zalenium");
        String zaleniumContainerId = containerCreation.id();
        dockerClient.startContainer(zaleniumContainerId);
        return zaleniumContainerId;
    }

    private void cleanUpZaleniumContainer(DockerClient dockerClient) throws DockerException, InterruptedException {
        // Removing first all docker-selenium containers
        List<Container> containerList = dockerClient.listContainers(DockerClient.ListContainersParam.allContainers());
        for (Container container : containerList) {
            String containerName = "zalenium";
            if (container.names().get(0).contains(containerName)) {
                dockerClient.stopContainer(container.id(), 5);
                dockerClient.removeContainer(container.id());
            }
        }
    }

    private Map<String, Object> getCapabilitySupportedByDockerSelenium() {
        Map<String, Object> requestedCapability = new HashMap<>();
        requestedCapability.put(CapabilityType.BROWSER_NAME, BrowserType.CHROME);
        requestedCapability.put(CapabilityType.PLATFORM, Platform.LINUX);
        return requestedCapability;
    }
}
