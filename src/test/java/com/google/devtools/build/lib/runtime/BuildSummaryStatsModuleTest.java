// Copyright 2017 The Bazel Authors. All rights reserved.
// //
// // Licensed under the Apache License, Version 2.0 (the "License");
// // you may not use this file except in compliance with the License.
// // You may obtain a copy of the License at
// //
// //    http://www.apache.org/licenses/LICENSE-2.0
// //
// // Unless required by applicable law or agreed to in writing, software
// // distributed under the License is distributed on an "AS IS" BASIS,
// // WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// // See the License for the specific language governing permissions and
// // limitations under the License.
package com.google.devtools.build.lib.runtime;

import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.buildtool.buildevent.BuildCompleteEvent;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.ActionResultReceivedEvent;
import com.google.devtools.build.lib.buildtool.BuildResult;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import java.util.Properties;
import java.util.Optional;
import java.lang.reflect.Field;
import java.time.Duration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@RunWith(JUnit4.class)
public class BuildSummaryStatsModuleTest {
  private BuildSummaryStatsModule buildSummaryStatsModule;
  private Reporter reporterMock;
  private ActionKeyContext actionKeyContextMock;
  private Class tBSSM;
  private boolean MacOS = false;

  @Before
  public void setUp() throws Exception {
    Properties props = System.getProperties();
    String OSName = props.getProperty("os.name");
    MacOS = OSName.contains("Mac OS");
    CommandEnvironment env = mock(CommandEnvironment.class);
    actionKeyContextMock = mock(ActionKeyContext.class);
    reporterMock = mock(Reporter.class);
    SkyframeExecutor skyframeExecutorMock = mock(SkyframeExecutor.class);
    EventBus eventBusMock = mock(EventBus.class);
    when(env.getReporter()).thenReturn(reporterMock);
    when(env.getSkyframeExecutor()).thenReturn(skyframeExecutorMock);
    when(skyframeExecutorMock.getActionKeyContext()).thenReturn(actionKeyContextMock);
    when(env.getEventBus()).thenReturn(eventBusMock);
    buildSummaryStatsModule = new BuildSummaryStatsModule();
    buildSummaryStatsModule.beforeCommand(env);
    /* With reflection mechanism, set some fake values to get CPU info. */
    tBSSM = buildSummaryStatsModule.getClass();
    Field field0 = tBSSM.getDeclaredField("statsSummary");
    field0.setAccessible(true);
    field0.setBoolean(buildSummaryStatsModule, true);
  }

  private ActionResultReceivedEvent createActionEvent(Duration userTime, Duration systemTime) {
    ActionResult result = mock(ActionResult.class);

    if(userTime == null){
      when(result.cumulativeCommandExecutionUserTime()).thenReturn(Optional.empty());
    }
    else{
      when(result.cumulativeCommandExecutionUserTime()).thenReturn(Optional.of(userTime));
    }

    if(systemTime == null){
      when(result.cumulativeCommandExecutionSystemTime()).thenReturn(Optional.empty());
    }
    else{
      when(result.cumulativeCommandExecutionSystemTime()).thenReturn(Optional.of(systemTime));
    }

    when(result.spawnResults()).thenReturn(ImmutableList.of());
    return new ActionResultReceivedEvent(null, result);
  }

  private BuildCompleteEvent createBuildEvent() {
    BuildResult buildResult = new BuildResult(1000);
    buildResult.setStopTime(2000);
    return new BuildCompleteEvent(buildResult);
  }

  @Test
  public void allCpuTimesAreSummarized() throws Exception {
    ActionResultReceivedEvent action1 = createActionEvent(Duration.ofSeconds(50), Duration.ofSeconds(20));
    ActionResultReceivedEvent action2 = createActionEvent(Duration.ofSeconds(5), Duration.ofSeconds(2));
    buildSummaryStatsModule.actionResultReceived(action1);
    buildSummaryStatsModule.actionResultReceived(action2);
    Field field1 = tBSSM.getDeclaredField("cpuTimeForBazelJvm");
    field1.setAccessible(true);
    field1.setLong(buildSummaryStatsModule, 11000);
    buildSummaryStatsModule.buildComplete(createBuildEvent());
    if(MacOS) {
      verify(reporterMock).handle(Event.info("CPU time 88,00s (user 55,00s, system 22,00s, bazel 11,00s)"));
    }
    else{
      verify(reporterMock).handle(Event.info("CPU time 88.00s (user 55.00s, system 22.00s, bazel 11.00s)"));
    }
  }

  @Test
  public void mixOfActionsWithKnownAndUnknownCpuTimesResultInUnknownTimes() throws Exception {
    // First action with unknown values
    ActionResultReceivedEvent action1 = createActionEvent(null, null);
    // Followed by action with known values
    ActionResultReceivedEvent action2 = createActionEvent(Duration.ofSeconds(50), Duration.ofSeconds(20));
    buildSummaryStatsModule.actionResultReceived(action1);
    buildSummaryStatsModule.actionResultReceived(action2);
    buildSummaryStatsModule.buildComplete(createBuildEvent());
    verify(reporterMock).handle(Event.info("CPU time ???s (user ???s, system ???s, bazel ???s)"));
  }

  @Test
  public void knownAndUnknownCpuTimesForAnActionIsReportedAndSumBecomeUnknown() throws Exception {
    ActionResultReceivedEvent action1 = createActionEvent(Duration.ofSeconds(50), null);
    buildSummaryStatsModule.actionResultReceived(action1);
    buildSummaryStatsModule.buildComplete(createBuildEvent());
    if(MacOS) {
      verify(reporterMock).handle(Event.info("CPU time ???s (user 50,00s, system ???s, bazel ???s)"));
    }
    else{
      verify(reporterMock).handle(Event.info("CPU time ???s (user 50.00s, system ???s, bazel ???s)"));
    }
  }

  @Test
  public void reusedBuildSummaryStatsModuleIsClearedBetweenBuilds() throws Exception {
    ActionResultReceivedEvent action1 = createActionEvent(Duration.ofSeconds(50),
                                                          Duration.ofSeconds(20));
    buildSummaryStatsModule.actionResultReceived(action1);
    Field field1 = tBSSM.getDeclaredField("cpuTimeForBazelJvm");
    field1.setAccessible(true);
    field1.setLong(buildSummaryStatsModule, 10000);
    buildSummaryStatsModule.buildComplete(createBuildEvent());
    if(MacOS) {
      verify(reporterMock).handle(Event.info("CPU time 80,00s (user 50,00s, system 20,00s, bazel 10,00s)"));
    }
    else{
      verify(reporterMock).handle(Event.info("CPU time 80.00s (user 50.00s, system 20.00s, bazel 10.00s)"));
    }
    // One more build, and verify that previous values are not preserved.
    buildSummaryStatsModule.buildComplete(createBuildEvent());
    if(MacOS) {
      verify(reporterMock).handle(Event.info("CPU time ???s (user 0,00s, system 0,00s, bazel ???s)"));
    }
    else{
      verify(reporterMock).handle(Event.info("CPU time ???s (user 0.00s, system 0.00s, bazel ???s)"));
    }
  }
}
