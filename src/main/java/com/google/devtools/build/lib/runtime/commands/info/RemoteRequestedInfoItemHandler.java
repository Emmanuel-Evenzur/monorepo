// Copyright 2025 The Bazel Authors. All rights reserved.
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
// limitations under the License.result.add("--remote_info_request");
package com.google.devtools.build.lib.runtime.commands.info;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.commands.PathToReplaceUtils;
import com.google.devtools.build.lib.server.CommandProtos.InfoItem;
import com.google.devtools.build.lib.server.CommandProtos.InfoResponse;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.TextFormat;
import java.io.IOException;

/** Collects {@link InfoItem}s and sends them to the remote client via response extensions. */
class RemoteRequestedInfoItemHandler implements InfoItemHandler {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final CommandEnvironment env;
  private final ImmutableList.Builder<InfoItem> infoItemsBuilder;
  private final boolean printKeys;

  RemoteRequestedInfoItemHandler(CommandEnvironment env, boolean printKeys) {
    this.env = env;
    this.infoItemsBuilder = ImmutableList.builder();
    this.printKeys = printKeys;
  }

  @Override
  public void addInfoItem(String key, byte[] value) {
    infoItemsBuilder.add(
        InfoItem.newBuilder().setKey(key).setValue(ByteString.copyFrom(value)).build());
  }

  @Override
  public void close() throws IOException {
    ImmutableList<InfoItem> infoItems = infoItemsBuilder.build();
    InfoResponse infoResponse =
        InfoResponse.newBuilder()
            .addAllPathToReplace(PathToReplaceUtils.getPathsToReplace(env))
            .addAllInfoItem(infoItems)
            .setPrintKeys(printKeys)
            .build();

    logger.atFine().log(
        "Blaze info is invoked by a remote client. InfoResponse = %s",
        TextFormat.printer().emittingSingleLine(true).printToString(infoResponse));

    env.addResponseExtensions(ImmutableList.of(Any.pack(infoResponse)));
  }
}
