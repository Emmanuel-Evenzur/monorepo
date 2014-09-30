// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.webstatusserver;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.syntax.Label;
import com.google.devtools.build.lib.view.test.TestStatus.BlazeTestStatus;
import com.google.devtools.build.lib.view.test.TestStatus.TestCase;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Stores information about one build command. The data is stored in JSON so that it can be
 * can be easily fed to frontend.
 *
 * <p> The information is grouped into following structures:
 * <ul>
 * <li> {@link #options} contain information about the build known when it starts but before
 *      anything is actually compiled/run
 * <li> {@link #testRules} contain summaries about test rules that already completed
 * <li> {@link #testCases} contain detailed information about each test case ran, for now they're
 *
 * </ul>
 */
public class WebStatusBuildLog {
  private Gson gson = new Gson();
  private boolean complete = false;
  private static final Logger LOG =
      Logger.getLogger(WebStatusEventCollector.class.getCanonicalName());
  private Map<String, JsonElement> options = new HashMap<String, JsonElement>();
  private Map<Label, JsonObject> testRules = new HashMap<Label, JsonObject>();
  private Map<String, JsonObject> testCases = new HashMap<String, JsonObject>();

  public WebStatusBuildLog addInfo(String key, Object value) {
    options.put(key, gson.toJsonTree(value));
    return this;
  }

  public void finish() {
    options = ImmutableMap.copyOf(options);
    complete = true;
  }

  public Map<String, JsonElement> getOptions() {
    return ImmutableMap.copyOf(options);
  }

  public Map<Label, JsonObject> getTestSummaries() {
    // TODO(marcinf): immutability
    // The result is not really immutable - addProperty can be called on values to modify them.
    // however, this behaviour is not intended, but there is no good fix for now (other than
    // deepcopying everything)
    return ImmutableMap.copyOf(testRules);
  }

  public ImmutableMap<String, JsonObject> getTestCases() {
    // See comment for {@link #getTestSummaries}
    return ImmutableMap.copyOf(testCases);
  }

  public boolean finished() {
    return complete;
  }

  public void addTestTarget(Label label) {
    if (!testRules.containsKey(label)) {
      testRules.put(label, new JsonObject());
    } else {
      // TODO(marcinf): figure out if there are any situations it can happen
    }
  }

  public void addTestSummary(Label label, BlazeTestStatus status, List<Long> testTimes,
      boolean isCached) {
    testRules.get(label).addProperty("status", status.toString());
    testRules.get(label).add("times", gson.toJsonTree(testTimes));
    testRules.get(label).addProperty("cached", isCached);
  }

  public void addTargetComplete(Label label) {
    if (testRules.containsKey(label)) {
      testRules.get(label).addProperty("status", "built");
    } else {
      LOG.info("Unhandled target: " + label);
    }
  }

  public JsonObject serializeTestCases(TestCase testCase) {
    JsonObject result = new JsonObject();
    JsonArray children = new JsonArray();
    result.addProperty("name", testCase.getName());
    result.addProperty("className", testCase.getClassName());
    result.addProperty("result", testCase.getResult());
    result.addProperty("time", testCase.getRunDurationMillis());
    for (TestCase child : testCase.getChildList()) {
      children.add(serializeTestCases(child));
    }
    result.add("children", children);
    return result;
  }

  public void addTestResult(Label label, TestCase testCase, int shardNumber) {
    // For now ignore the shard number and just merge all the data
    LOG.info(serializeTestCases(testCase).toString());
    testCases.put(label.toShorthandString() + " " + shardNumber, serializeTestCases(testCase));
  }
}
