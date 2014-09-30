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
package com.google.devtools.build.lib.syntax;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.events.Location;

import java.util.Map;

import javax.annotation.Nullable;

/**
 * An interface for objects behaving like Skylark structs.
 */
// TODO(bazel-team): type checks
public interface ClassObject {

  /**
   * Returns the value associated with the name field in this struct,
   * or null if the field does not exist.
   */
  @Nullable
  Object getValue(String name);

  /**
   * Returns the fields of this struct.
   */
  ImmutableCollection<String> getKeys();

  /**
   * An implementation class of ClassObject for structs created in Skylark code.
   */
  @Immutable
  public class SkylarkClassObject implements ClassObject {

    private final ImmutableMap<String, Object> values;
    private final Location creationLoc;

    public SkylarkClassObject(Map<String, Object> values) {
      this.values = ImmutableMap.copyOf(values);
      this.creationLoc = null;
    }

    public SkylarkClassObject(Map<String, Object> values, Location creationLoc) {
      this.values = ImmutableMap.copyOf(values);
      this.creationLoc = Preconditions.checkNotNull(creationLoc);
    }

    @Override
    public Object getValue(String name) {
      return values.get(name);
    }

    @Override
    public ImmutableCollection<String> getKeys() {
      return values.keySet();
    }

    public Location getCreationLoc() {
      return Preconditions.checkNotNull(creationLoc,
          "This struct was not created in a Skylark code");
    }

    static SkylarkClassObject concat(
        SkylarkClassObject lval, SkylarkClassObject rval, Location loc) throws EvalException {
      SetView<String> commonFields = Sets.intersection(lval.values.keySet(), rval.values.keySet());
      if (!commonFields.isEmpty()) {
        throw new EvalException(loc, "Cannot concat structs with common field(s): "
            + Joiner.on(",").join(commonFields));
      }
      return new SkylarkClassObject(ImmutableMap.<String, Object>builder()
          .putAll(lval.values).putAll(rval.values).build(), loc);
    }
  }
}
