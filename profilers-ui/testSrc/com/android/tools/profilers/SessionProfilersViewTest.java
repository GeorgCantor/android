/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.profilers;

import static com.android.tools.idea.transport.faketransport.FakeTransportService.FAKE_DEVICE;
import static com.android.tools.idea.transport.faketransport.FakeTransportService.FAKE_DEVICE_ID;
import static com.android.tools.idea.transport.faketransport.FakeTransportService.FAKE_DEVICE_NAME;
import static com.android.tools.idea.transport.faketransport.FakeTransportService.FAKE_PROCESS;
import static com.android.tools.idea.transport.faketransport.FakeTransportService.FAKE_PROCESS_NAME;
import static com.android.tools.profilers.ProfilersTestData.DEFAULT_AGENT_ATTACHED_RESPONSE;
import static com.android.tools.profilers.ProfilersTestData.DEFAULT_AGENT_UNSPECIFIED_RESPONSE;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assume.assumeFalse;

import com.android.sdklib.AndroidVersion;
import com.android.tools.adtui.TreeWalker;
import com.android.tools.adtui.chart.linechart.LineChart;
import com.android.tools.adtui.model.FakeTimer;
import com.android.tools.adtui.model.Timeline;
import com.android.tools.adtui.stdui.CommonButton;
import com.android.tools.adtui.stdui.ContextMenuItem;
import com.android.tools.adtui.swing.FakeUi;
import com.android.tools.idea.transport.faketransport.FakeGrpcServer;
import com.android.tools.idea.transport.faketransport.FakeTransportService;
import com.android.tools.profiler.proto.Common;
import com.android.tools.profilers.cpu.CpuMonitorTooltip;
import com.android.tools.profilers.cpu.CpuProfilerStage;
import com.android.tools.profilers.energy.EnergyMonitorTooltip;
import com.android.tools.profilers.energy.EnergyProfilerStage;
import com.android.tools.profilers.memory.FakeCaptureObjectLoader;
import com.android.tools.profilers.memory.MainMemoryProfilerStage;
import com.android.tools.profilers.memory.MemoryCaptureStage;
import com.android.tools.profilers.memory.MemoryMonitorTooltip;
import com.android.tools.profilers.sessions.SessionsView;
import com.google.common.truth.Truth;
import com.google.wireless.android.sdk.stats.AndroidProfilerEvent;
import com.intellij.openapi.ui.ThreeComponentsSplitter;
import com.intellij.testFramework.ApplicationRule;
import com.intellij.testFramework.DisposableRule;
import com.intellij.testFramework.EdtRule;
import com.intellij.testFramework.RunsInEdt;
import icons.StudioIcons;
import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunsInEdt
@RunWith(Parameterized.class)
public class SessionProfilersViewTest {

  private static final Common.Session SESSION_O = Common.Session.newBuilder().setSessionId(2).setStartTimestamp(FakeTimer.ONE_SECOND_IN_NS)
    .setEndTimestamp(FakeTimer.ONE_SECOND_IN_NS * 2).setPid(1).build();
  private static final Common.SessionMetaData SESSION_O_METADATA = Common.SessionMetaData.newBuilder().setSessionId(2).setJvmtiEnabled(true)
    .setSessionName("App Device").setType(Common.SessionMetaData.SessionType.FULL).setStartTimestampEpochMs(1).build();
  private static final int NEW_DEVICE_ID = 1;
  private static final int NEW_PROCESS_ID = 2;

  private final FakeTimer myTimer = new FakeTimer();
  private final FakeTransportService myService;
  private final boolean myIsTestingProfileable;

  public SessionProfilersViewTest(boolean isTestingProfileable) {
    myIsTestingProfileable = isTestingProfileable;
    myService = isTestingProfileable
                ? new FakeTransportService(myTimer, true, AndroidVersion.VersionCodes.S, Common.Process.ExposureLevel.PROFILEABLE)
                : new FakeTransportService(myTimer);
    myGrpcChannel = FakeGrpcServer.createFakeGrpcServer("StudioProfilerTestChannel", myService);
  }

  @Rule public final FakeGrpcServer myGrpcChannel;
  @Rule public final EdtRule myEdtRule = new EdtRule();
  @Rule public final ApplicationRule myAppRule = new ApplicationRule();  // For initializing HelpTooltip.
  @Rule public final DisposableRule myDisposableRule = new DisposableRule();

  private StudioProfilers myProfilers;
  private FakeIdeProfilerServices myProfilerServices = new FakeIdeProfilerServices();
  private SessionProfilersView myView;
  private FakeUi myUi;

  @Before
  public void setUp() {
    myProfilerServices.enableEnergyProfiler(true);
    myProfilers = new StudioProfilers(new ProfilerClient(myGrpcChannel.getChannel()), myProfilerServices, myTimer);
    myProfilers.setPreferredProcess(FAKE_DEVICE_NAME, FAKE_PROCESS_NAME, null);
    myView = new SessionProfilersView(myProfilers, new FakeIdeProfilerComponents(), myDisposableRule.getDisposable());
    myView.bind(FakeStage.class, FakeView::new);
    myTimer.tick(FakeTimer.ONE_SECOND_IN_NS);
    if (myIsTestingProfileable) {
      // We setup and profile a process, we assume that process has an agent attached by default.
      updateAgentStatus(FAKE_PROCESS.getPid(), DEFAULT_AGENT_ATTACHED_RESPONSE);
    }
    JComponent component = myView.getComponent();
    component.setSize(1024, 450);
    myUi = new FakeUi(component);
  }

  @Test
  public void testSameStageTransition() {
    FakeStage stage = new FakeStage(myProfilers, "Really?");
    myProfilers.setStage(stage);
    StageView view = myView.getStageView();

    myProfilers.setStage(stage);
    assertThat(myView.getStageView()).isEqualTo(view);
  }

  @Test
  public void testViewHasNoExceptionsWhenProfilersStop() {
    FakeStage stage = new FakeStage(myProfilers, "Really?");
    myProfilers.setStage(stage);
    StageView view = myView.getStageView();

    myProfilers.setStage(stage);
    myProfilers.stop();
    // Make sure no exceptions
  }

  @Test
  public void testMonitorExpansion() {
    assumeFalse(myIsTestingProfileable);
    // Set session to enable Energy Monitor.
    myService.addSession(SESSION_O, SESSION_O_METADATA);
    myTimer.tick(FakeTimer.ONE_SECOND_IN_NS);
    myProfilers.getSessionsManager().setSession(SESSION_O);
    myUi.layout();

    assertThat(myProfilers.getStage()).isInstanceOf(StudioMonitorStage.class);

    List<Point> points = new TreeWalker(myView.getComponent()).descendantStream()
      .filter(d -> d instanceof LineChart)
      .map(c -> myUi.getPosition(c))
      .collect(Collectors.toList());
    // Test that we have the expected number of monitors
    assertThat(points.size()).isEqualTo(3);

    // Test the first monitor goes to cpu profiler
    myUi.mouse.click(points.get(0).x + 1, points.get(0).y + 1);
    assertThat(myProfilers.getStage()).isInstanceOf(CpuProfilerStage.class);
    myProfilers.setMonitoringStage();

    myUi.layout();
    // Test the second monitor goes to memory profiler
    myUi.mouse.click(points.get(1).x + 1, points.get(1).y + 1);
    assertThat(myProfilers.getStage()).isInstanceOf(MainMemoryProfilerStage.class);
    myProfilers.setMonitoringStage();

    myUi.layout();
    // Test the fourth monitor goes to energy profiler
    myUi.mouse.click(points.get(2).x + 1, points.get(2).y + 1);
    assertThat(myProfilers.getStage()).isInstanceOf(EnergyProfilerStage.class);
    myProfilers.setMonitoringStage();
  }

  @Test
  public void testMonitorTooltip() {
    assumeFalse(myIsTestingProfileable);
    // Set Session to enable Energy monitor tooltip.
    myService.addSession(SESSION_O, SESSION_O_METADATA);
    myTimer.tick(FakeTimer.ONE_SECOND_IN_NS);
    myProfilers.getSessionsManager().setSession(SESSION_O);
    myUi.layout();

    assertThat(myProfilers.getStage()).isInstanceOf(StudioMonitorStage.class);
    StudioMonitorStage stage = (StudioMonitorStage)myProfilers.getStage();

    List<Point> points = new TreeWalker(myView.getComponent()).descendantStream()
      .filter(d -> d instanceof LineChart)
      .map(c -> myUi.getPosition(c))
      .collect(Collectors.toList());
    // Test that we have the expected number of monitors
    assertThat(points.size()).isEqualTo(3);

    // cpu monitor tooltip
    myUi.mouse.moveTo(points.get(0).x + 1, points.get(0).y + 1);
    assertThat(stage.getTooltip()).isInstanceOf(CpuMonitorTooltip.class);
    ProfilerMonitor cpuMonitor = ((CpuMonitorTooltip)stage.getTooltip()).getMonitor();
    stage.getMonitors().forEach(
      monitor -> Truth.assertWithMessage("Only the CPU Monitor should be focused.")
        .that(monitor.isFocused()).isEqualTo(monitor == cpuMonitor));

    // memory monitor tooltip
    myUi.mouse.moveTo(points.get(1).x + 1, points.get(1).y + 1);
    assertThat(stage.getTooltip()).isInstanceOf(MemoryMonitorTooltip.class);
    ProfilerMonitor memoryMonitor = ((MemoryMonitorTooltip)stage.getTooltip()).getMonitor();
    stage.getMonitors().forEach(
      monitor -> Truth.assertWithMessage("Only the Memory Monitor should be focused.")
        .that(monitor.isFocused()).isEqualTo(monitor == memoryMonitor));

    // energy monitor tooltip
    myUi.mouse.moveTo(points.get(2).x + 1, points.get(2).y + 1);
    assertThat(stage.getTooltip()).isInstanceOf(EnergyMonitorTooltip.class);
    ProfilerMonitor energyMonitor = ((EnergyMonitorTooltip)stage.getTooltip()).getMonitor();
    stage.getMonitors().forEach(
      monitor -> Truth.assertWithMessage("Only the Energy Monitor should be focused.")
        .that(monitor.isFocused()).isEqualTo(monitor == energyMonitor));

    // no tooltip
    myUi.mouse.moveTo(0, 0);
    assertThat(stage.getTooltip()).isNull();
    stage.getMonitors().forEach(monitor -> Truth.assertWithMessage("No monitor should be focused.").that(monitor.isFocused()).isFalse());
  }

  @Test
  public void testRememberSessionUiStates() {
    // Check that sessions is initially expanded
    assertThat(myView.getSessionsView().getCollapsed()).isFalse();

    // Fake a collapse action and re-create the StudioProfilerView, the session UI should now remain collapsed.
    myView.getSessionsView().getCollapseButton().doClick();
    StudioProfilers profilers = new StudioProfilers(new ProfilerClient(myGrpcChannel.getChannel()), myProfilerServices, myTimer);
    SessionProfilersView profilersView = new SessionProfilersView(profilers, new FakeIdeProfilerComponents(), myDisposableRule.getDisposable());
    assertThat(profilersView.getSessionsView().getCollapsed()).isTrue();

    // Fake a resize and re-create the StudioProfilerView, the session UI should maintain the previous dimension
    profilersView.getSessionsView().getExpandButton().doClick();
    ThreeComponentsSplitter splitter = (ThreeComponentsSplitter)profilersView.getComponent().getComponent(0);
    assertThat(splitter.getFirstSize()).isEqualTo(SessionsView.getComponentMinimizeSize(true).width);
    splitter.setSize(1024, 450);
    myUi.mouse.drag(splitter.getFirstSize(), 0, 10, 0);

    profilers = new StudioProfilers(new ProfilerClient(myGrpcChannel.getChannel()), myProfilerServices, myTimer);
    profilersView = new SessionProfilersView(profilers, new FakeIdeProfilerComponents(), myDisposableRule.getDisposable());
    assertThat(profilersView.getSessionsView().getCollapsed()).isFalse();
    assertThat(((ThreeComponentsSplitter)profilersView.getComponent().getComponent(0)).getFirstSize()).isEqualTo(splitter.getFirstSize());
  }

  @Test
  public void testGoLiveButtonStates() {
    // Check that go live is initially enabled and toggled
    JToggleButton liveButton = myView.getGoLiveButton();
    ArrayList<ContextMenuItem> contextMenuItems = ProfilerContextMenu.createIfAbsent(myView.getStageComponent()).getContextMenuItems();
    ContextMenuItem attachItem = null;
    ContextMenuItem detachItem = null;
    for (ContextMenuItem item : contextMenuItems) {
      if (item.getText().equals(SessionProfilersView.ATTACH_LIVE)) {
        attachItem = item;
      }
      else if (item.getText().equals(SessionProfilersView.DETACH_LIVE)) {
        detachItem = item;
      }
    }
    assertThat(attachItem).isNotNull();
    assertThat(detachItem).isNotNull();

    assertThat(myProfilers.getSessionsManager().isSessionAlive()).isTrue();
    assertThat(liveButton.isEnabled()).isTrue();
    assertThat(liveButton.isSelected()).isTrue();
    assertThat(attachItem.isEnabled()).isFalse();
    assertThat(detachItem.isEnabled()).isTrue();

    // Detaching from live should unselect the button.
    detachItem.run();
    assertThat(myProfilers.getSessionsManager().isSessionAlive()).isTrue();
    assertThat(liveButton.isEnabled()).isTrue();
    assertThat(liveButton.isSelected()).isFalse();
    assertThat(attachItem.isEnabled()).isTrue();
    assertThat(detachItem.isEnabled()).isFalse();
    assertThat(liveButton.getIcon()).isEqualTo(StudioIcons.Profiler.Toolbar.GOTO_LIVE);
    assertThat(liveButton.getToolTipText()).startsWith(SessionProfilersView.ATTACH_LIVE);

    // Attaching to live should select the button again.
    attachItem.run();
    assertThat(myProfilers.getSessionsManager().isSessionAlive()).isTrue();
    assertThat(liveButton.isEnabled()).isTrue();
    assertThat(liveButton.isSelected()).isTrue();
    assertThat(attachItem.isEnabled()).isFalse();
    assertThat(detachItem.isEnabled()).isTrue();
    assertThat(liveButton.getIcon()).isEqualTo(StudioIcons.Profiler.Toolbar.PAUSE_LIVE);
    assertThat(liveButton.getToolTipText()).startsWith(SessionProfilersView.DETACH_LIVE);

    // Stopping the session should disable and unselect the button
    endSession();
    Common.Session deadSession = myProfilers.getSessionsManager().getSelectedSession();
    assertThat(myProfilers.getSessionsManager().isSessionAlive()).isFalse();
    assertThat(liveButton.isEnabled()).isFalse();
    assertThat(liveButton.isSelected()).isFalse();
    assertThat(attachItem.isEnabled()).isFalse();
    assertThat(detachItem.isEnabled()).isFalse();

    startSessionWithNewDeviceAndProcess();
    if (myIsTestingProfileable) {
      updateAgentStatus(NEW_PROCESS_ID, DEFAULT_AGENT_ATTACHED_RESPONSE);
    }
    assertThat(myProfilers.getSessionsManager().isSessionAlive()).isTrue();
    // Live button should be selected when switching to a live session.
    assertThat(liveButton.isEnabled()).isTrue();
    assertThat(liveButton.isSelected()).isTrue();
    assertThat(attachItem.isEnabled()).isFalse();
    assertThat(detachItem.isEnabled()).isTrue();

    // Switching to a dead session should disable and unselect the button.
    myProfilers.getSessionsManager().setSession(deadSession);
    assertThat(liveButton.isEnabled()).isFalse();
    assertThat(liveButton.isSelected()).isFalse();
    assertThat(attachItem.isEnabled()).isFalse();
    assertThat(detachItem.isEnabled()).isFalse();
  }

  @Test
  public void testGoLiveButtonWhenToggleStreaming() {
    JToggleButton liveButton = myView.getGoLiveButton();
    assertThat(liveButton.isEnabled()).isTrue();
    myProfilers.getTimeline().setStreaming(false);
    assertThat(liveButton.isSelected()).isFalse();
    assertThat(liveButton.getIcon()).isEqualTo(StudioIcons.Profiler.Toolbar.GOTO_LIVE);
    assertThat(liveButton.getToolTipText()).startsWith(SessionProfilersView.ATTACH_LIVE);

    myProfilers.getTimeline().setStreaming(true);
    assertThat(liveButton.isSelected()).isTrue();
    assertThat(liveButton.getIcon()).isEqualTo(StudioIcons.Profiler.Toolbar.PAUSE_LIVE);
    assertThat(liveButton.getToolTipText()).startsWith(SessionProfilersView.DETACH_LIVE);
  }

  @Test
  public void testTimelineButtonEnableStates() {
    CommonButton zoomInButton = myView.getZoomInButton();
    CommonButton zoomOutButton = myView.getZoomOutButton();
    CommonButton resetButton = myView.getResetZoomButton();
    CommonButton frameSelectionButton = myView.getZoomToSelectionButton();
    JToggleButton liveButton = myView.getGoLiveButton();

    // A live session without agent should have all controls enabled
    assertThat(myProfilers.getSessionsManager().isSessionAlive()).isTrue();
    assertThat(zoomInButton.isEnabled()).isTrue();
    assertThat(zoomOutButton.isEnabled()).isTrue();
    assertThat(resetButton.isEnabled()).isTrue();
    assertThat(frameSelectionButton.isEnabled()).isFalse(); // Frame selection button is dependent on selection being available.
    assertThat(liveButton.isEnabled()).isTrue();

    // Updating the selection should enable the frame selection control.
    myProfilers.getTimeline().getSelectionRange().set(myProfilers.getTimeline().getDataRange());
    assertThat(zoomInButton.isEnabled()).isTrue();
    assertThat(zoomOutButton.isEnabled()).isTrue();
    assertThat(resetButton.isEnabled()).isTrue();
    assertThat(frameSelectionButton.isEnabled()).isTrue();
    assertThat(liveButton.isEnabled()).isTrue();

    // Stopping the session should disable the live control
    endSession();
    assertThat(zoomInButton.isEnabled()).isTrue();
    assertThat(zoomOutButton.isEnabled()).isTrue();
    assertThat(resetButton.isEnabled()).isTrue();
    assertThat(frameSelectionButton.isEnabled()).isTrue();
    assertThat(liveButton.isEnabled()).isFalse();

    // Starting a session that is waiting for an agent to initialize should have all controls disabled.
    startSessionWithNewDeviceAndProcess();
    updateAgentStatus(NEW_PROCESS_ID, DEFAULT_AGENT_UNSPECIFIED_RESPONSE);
    assertThat(zoomInButton.isEnabled()).isFalse();
    assertThat(zoomOutButton.isEnabled()).isFalse();
    assertThat(resetButton.isEnabled()).isFalse();
    assertThat(frameSelectionButton.isEnabled()).isFalse();
    assertThat(liveButton.isEnabled()).isFalse();

    // Controls should be enabled after agent is attached.
    updateAgentStatus(NEW_PROCESS_ID, DEFAULT_AGENT_ATTACHED_RESPONSE);
    assertThat(zoomInButton.isEnabled()).isTrue();
    assertThat(zoomOutButton.isEnabled()).isTrue();
    assertThat(resetButton.isEnabled()).isTrue();
    assertThat(frameSelectionButton.isEnabled()).isFalse();
    assertThat(liveButton.isEnabled()).isTrue();

    // Setting to an empty session should have all controls disabled.
    myProfilers.getSessionsManager().setSession(Common.Session.getDefaultInstance());
    assertThat(zoomInButton.isEnabled()).isFalse();
    assertThat(zoomOutButton.isEnabled()).isFalse();
    assertThat(resetButton.isEnabled()).isFalse();
    assertThat(frameSelectionButton.isEnabled()).isFalse();
    assertThat(liveButton.isEnabled()).isFalse();
  }

  @Test
  public void testLoadingPanelWhileWaitingForPreferredProcess() {
    final String FAKE_PROCESS_2 = "FakeProcess2";
    assertThat(myView.getStageViewComponent().isVisible()).isTrue();
    assertThat(myView.getStageLoadingComponent().isVisible()).isFalse();

    // Sets a preferred process, the UI should wait and show the loading panel.
    endSession();
    updatePreferredProcess(FAKE_DEVICE_NAME, FAKE_PROCESS_2);
    assertThat(myProfilers.getAutoProfilingEnabled()).isTrue();
    assertThat(myView.getStageViewComponent().isVisible()).isFalse();
    assertThat(myView.getStageLoadingComponent().isVisible()).isTrue();

    Common.Process process = Common.Process.newBuilder()
      .setPid(NEW_PROCESS_ID)
      .setDeviceId(FAKE_DEVICE_ID)
      .setState(Common.Process.State.ALIVE)
      .setName(FAKE_PROCESS_2)
      .setExposureLevel(myIsTestingProfileable ? Common.Process.ExposureLevel.PROFILEABLE : Common.Process.ExposureLevel.DEBUGGABLE)
      .build();
    startSession(FAKE_DEVICE, process);
    if (myIsTestingProfileable) {
      updateAgentStatus(NEW_PROCESS_ID, DEFAULT_AGENT_ATTACHED_RESPONSE);
    }

    // Preferred process is found, session begins and the loading stops.
    assertThat(myView.getStageViewComponent().isVisible()).isTrue();
    assertThat(myView.getStageLoadingComponent().isVisible()).isFalse();
  }

  @Test
  public void testLoadingPanelWhileWaitingForAgentAttach() {
    assumeFalse(myIsTestingProfileable); // hardcoded `FAKE_DEVICE` is different than one used for the profileable test
    final String FAKE_PROCESS_2 = "FakeProcess2";
    assertThat(myView.getStageViewComponent().isVisible()).isTrue();
    assertThat(myView.getStageLoadingComponent().isVisible()).isFalse();

    Common.Process process = Common.Process.newBuilder()
      .setPid(NEW_PROCESS_ID)
      .setDeviceId(FAKE_DEVICE_ID)
      .setState(Common.Process.State.ALIVE)
      .setName(FAKE_PROCESS_2)
      .setExposureLevel(Common.Process.ExposureLevel.DEBUGGABLE)
      .build();
    startSession(FAKE_DEVICE, process);
    updateAgentStatus(NEW_PROCESS_ID, DEFAULT_AGENT_UNSPECIFIED_RESPONSE);

    // Agent is detached, the UI should wait and show the loading panel.
    assertThat(myView.getStageViewComponent().isVisible()).isFalse();
    assertThat(myView.getStageLoadingComponent().isVisible()).isTrue();

    updateAgentStatus(NEW_PROCESS_ID, DEFAULT_AGENT_ATTACHED_RESPONSE);

    // Attach status is detected, loading should stop.
    assertThat(myView.getStageViewComponent().isVisible()).isTrue();
    assertThat(myView.getStageLoadingComponent().isVisible()).isFalse();
  }

  @Test
  public void testNullStageIfDeviceIsUnsupported() {
    final String FAKE_PROCESS_2 = "FakeProcess2";
    final String UNSUPPORTED_DEVICE_NAME = "UnsupportedDevice";
    final String UNSUPPORTED_REASON = "This device is unsupported";
    assertThat(myView.getStageViewComponent().isVisible()).isTrue();
    assertThat(myView.getStageLoadingComponent().isVisible()).isFalse();

    // Disconnect the current device and connect to an unsupported device.
    Common.Device dead_device = FAKE_DEVICE.toBuilder().setState(Common.Device.State.DISCONNECTED).build();
    Common.Device device = Common.Device.newBuilder()
      .setDeviceId(999)
      .setSerial(UNSUPPORTED_DEVICE_NAME)
      .setApiLevel(AndroidVersion.VersionCodes.KITKAT)
      .setFeatureLevel(AndroidVersion.VersionCodes.KITKAT)
      .setModel(UNSUPPORTED_DEVICE_NAME)
      .setState(Common.Device.State.ONLINE)
      .setUnsupportedReason(UNSUPPORTED_REASON)
      .build();
    Common.Process process = Common.Process.newBuilder()
      .setPid(NEW_PROCESS_ID)
      .setDeviceId(device.getDeviceId())
      .setState(Common.Process.State.ALIVE)
      .setName(FAKE_PROCESS_2)
      .setExposureLevel(myIsTestingProfileable ? Common.Process.ExposureLevel.PROFILEABLE : Common.Process.ExposureLevel.DEBUGGABLE)
      .build();
    myService.updateDevice(FAKE_DEVICE, dead_device);

    // Set the preferred device to the unsupported one. Loading screen will be displayed.
    endSession();
    updatePreferredProcess(UNSUPPORTED_DEVICE_NAME, FAKE_PROCESS_2);
    assertThat(myView.getStageViewComponent().isVisible()).isFalse();
    assertThat(myView.getStageLoadingComponent().isVisible()).isTrue();

    // Preferred device is found. Loading stops and null stage should be displayed with the unsupported reason.
    myService.addDevice(device);
    myService.addProcess(device, process);
    myTimer.tick(FakeTimer.ONE_SECOND_IN_NS);
    myProfilers.setProcess(device, process);
    assertThat(myView.getStageViewComponent().isVisible()).isTrue();
    assertThat(myView.getStageLoadingComponent().isVisible()).isFalse();
  }

  @Test
  public void nonTimelineStageHidesRightToolbar_timelineStageShowsRightToolbar() {
    myProfilers.setStage(new MemoryCaptureStage(myProfilers, new FakeCaptureObjectLoader(), null, null));
    assertThat(myView.getRightToolbar().isVisible()).isFalse();

    myProfilers.setStage(new MainMemoryProfilerStage(myProfilers));
    assertThat(myView.getRightToolbar().isVisible()).isTrue();
  }

  private void startSessionWithNewDeviceAndProcess() {
    Common.Device onlineDevice = Common.Device.newBuilder()
      .setDeviceId(NEW_DEVICE_ID)
      .setFeatureLevel(AndroidVersion.VersionCodes.O)
      .setState(Common.Device.State.ONLINE)
      .build();
    Common.Process onlineProcess = Common.Process.newBuilder()
      .setPid(NEW_PROCESS_ID)
      .setDeviceId(NEW_DEVICE_ID)
      .setState(Common.Process.State.ALIVE)
      .setExposureLevel(myIsTestingProfileable ? Common.Process.ExposureLevel.PROFILEABLE : Common.Process.ExposureLevel.DEBUGGABLE)
      .build();
    startSession(onlineDevice, onlineProcess);
  }

  private void startSession(Common.Device device, Common.Process process) {
    myService.addDevice(device);
    updateProcess(device, process);
  }

  private void updateProcess(Common.Device device, Common.Process process) {
    myService.addProcess(device, process);
    myTimer.tick(FakeTimer.ONE_SECOND_IN_NS);

    myProfilers.setProcess(device, process);
    myTimer.tick(FakeTimer.ONE_SECOND_IN_NS);
  }

  private void updateAgentStatus(int pid, Common.AgentData agentData) {
    long sessionStreamId = myProfilers.getSession().getStreamId();
    myService.addEventToStream(sessionStreamId, Common.Event.newBuilder()
      .setPid(pid)
      .setKind(Common.Event.Kind.AGENT)
      .setAgentData(agentData)
      .build());
    myTimer.tick(FakeTimer.ONE_SECOND_IN_NS);
  }

  private void endSession() {
    myProfilers.getSessionsManager().endCurrentSession();
    myTimer.tick(FakeTimer.ONE_SECOND_IN_NS);
  }

  private void updatePreferredProcess(String preferredDeviceName, String preferredProcessName) {
    myProfilers.setPreferredProcess(preferredDeviceName, preferredProcessName, null);
    myTimer.tick(FakeTimer.ONE_SECOND_IN_NS);
  }

  static class FakeView extends StageView<FakeStage> {

    public FakeView(@NotNull SessionProfilersView profilersView, @NotNull FakeStage stage) {
      super(profilersView, stage);
    }

    @Override
    public JComponent getToolbar() {
      return new JPanel();
    }
  }

  @Parameterized.Parameters
  public static List<Boolean> isTestingProfileable() {
    return Arrays.asList(false, true);
  }
}