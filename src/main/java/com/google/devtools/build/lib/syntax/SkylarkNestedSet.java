// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.syntax;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.events.Location;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A generic type safe NestedSet wrapper for Skylark.
 */
@SkylarkModule(name = "NestedSet", doc = "An efficient nested set for Skylark")
@Immutable
public final class SkylarkNestedSet implements Iterable<Object> {

  private final Class<?> genericType;
  private final List<Object> items;
  private final List<NestedSet<Object>> transitiveItems;
  private final NestedSet<?> set;

  public SkylarkNestedSet(Order order, Object item, Location loc) throws EvalException {
    this(order, null, item, loc, new ArrayList<Object>(), new ArrayList<NestedSet<Object>>());
  }

  public SkylarkNestedSet(SkylarkNestedSet left, Object right, Location loc) throws EvalException {
    this(left.set.getOrder(), left.genericType, right, loc,
        new ArrayList<Object>(left.items), new ArrayList<NestedSet<Object>>(left.transitiveItems));
  }

  // This is safe because of the type checking
  @SuppressWarnings("unchecked")
  private SkylarkNestedSet(Order order, Class<?> genericType, Object item, Location loc,
      List<Object> items, List<NestedSet<Object>> transitiveItems) throws EvalException {

    // Adding the item
    if (item instanceof SkylarkNestedSet) {
      SkylarkNestedSet nestedSet = (SkylarkNestedSet) item;
      if (!nestedSet.isEmpty()) {
        genericType = checkType(genericType, nestedSet.genericType, loc);
        transitiveItems.add((NestedSet<Object>) nestedSet.set);
      }
    } else if (item instanceof SkylarkList) {
      // TODO(bazel-team): we should check ImmutableList here but it screws up genrule at line 43
      for (Object object : (SkylarkList) item) {
        genericType = checkType(genericType, object.getClass(), loc);
        items.add(object);
      }
    } else {
      throw new EvalException(loc,
          String.format("cannot add '%s'-s to nested sets", EvalUtils.getDatatypeName(item)));
    }
    this.genericType = genericType;

    // Initializing the real nested set
    NestedSetBuilder<Object> builder = new NestedSetBuilder<Object>(order);
    builder.addAll(items);
    try {
      for (NestedSet<Object> nestedSet : transitiveItems) {
        builder.addTransitive(nestedSet);
      }
    } catch (IllegalStateException e) {
      throw new EvalException(loc, e.getMessage());
    }
    this.set = builder.build();
    this.items = ImmutableList.copyOf(items);
    this.transitiveItems = ImmutableList.copyOf(transitiveItems);
  }

  /**
   * Returns a type safe SkylarkNestedSet. Use this instead of the constructor if possible.
   */
  public static <T> SkylarkNestedSet of(Class<T> genericType, NestedSet<T> set) {
    return new SkylarkNestedSet(genericType, set);
  }

  /**
   * A not type safe constructor for SkylarkNestedSet. It's discouraged to use it unless type
   * generic safety is guaranteed from the caller side.
   */
  SkylarkNestedSet(Class<?> genericType, NestedSet<?> set) {
    // This is here for the sake of FuncallExpression.
    this.genericType = Preconditions.checkNotNull(genericType, "type cannot be null");
    this.set = Preconditions.checkNotNull(set, "set cannot be null");
    this.items = null;
    this.transitiveItems = null;
  }

  private static Class<?> checkType(Class<?> builderType, Class<?> itemType, Location loc)
      throws EvalException {
    if (Map.class.isAssignableFrom(itemType) || SkylarkList.class.isAssignableFrom(itemType)) {
      throw new EvalException(loc, String.format("nested set item is a collection (type of %s)",
          EvalUtils.getDataTypeNameFromClass(itemType)));
    }
    if (!EvalUtils.isSkylarkImmutable(itemType)) {
      throw new EvalException(loc, String.format("nested set item is not immutable (type of %s)",
          EvalUtils.getDataTypeNameFromClass(itemType)));
    }
    if (builderType == null) {
      return itemType;
    }
    if (!builderType.equals(itemType)) {
      throw new EvalException(loc, String.format(
          "nested set item is type of %s but the nested set accepts only %s-s",
          EvalUtils.getDataTypeNameFromClass(itemType),
          EvalUtils.getDataTypeNameFromClass(builderType)));
    }
    return builderType;
  }

  /**
   * Returns the NestedSet embedded in this SkylarkNestedSet if it is of the parameter type.
   */
  // The precondition ensures generic type safety
  @SuppressWarnings("unchecked")
  public <T> NestedSet<T> getSet(Class<T> type) {
    // Empty sets don't need have to have a type since they don't have items
    if (set.isEmpty()) {
      return (NestedSet<T>) set;
    }
    Preconditions.checkArgument(type.isAssignableFrom(genericType),
        String.format("Expected %s as a type but got %s",
            EvalUtils.getDataTypeNameFromClass(type),
            EvalUtils.getDataTypeNameFromClass(genericType)));
    return (NestedSet<T>) set;
  }

  // For some reason this cast is unsafe in Java
  @SuppressWarnings("unchecked")
  @Override
  public Iterator<Object> iterator() {
    return (Iterator<Object>) set.iterator();
  }

  public Collection<Object> toCollection() {
    return ImmutableList.copyOf(set.toCollection());
  }

  @SkylarkCallable(doc = "Returns true if this file set is empty.")
  public boolean isEmpty() {
    return genericType == null;
  }

  @VisibleForTesting
  public Class<?> getGenericTypeInfo() {
    return genericType;
  }
}
