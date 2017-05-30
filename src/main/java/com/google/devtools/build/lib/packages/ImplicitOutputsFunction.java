// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages;

import static com.google.devtools.build.lib.syntax.SkylarkType.castMap;
import static java.util.Collections.singleton;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.escape.Escaper;
import com.google.common.escape.Escapers;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.ClassObject;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkCallbackFunction;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.util.StringUtil;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * A function interface allowing rules to specify their set of implicit outputs
 * in a more dynamic way than just simple template-substitution.  For example,
 * the set of implicit outputs may be a function of rule attributes.
 */
public abstract class ImplicitOutputsFunction {

  /**
   * Implicit output functions for Skylark supporting key value access of expanded implicit outputs.
   */
  public abstract static class SkylarkImplicitOutputsFunction extends ImplicitOutputsFunction {

    public abstract ImmutableMap<String, TemplateSubstitution> calculateOutputs(AttributeMap map)
        throws EvalException, InterruptedException;

    @Override
    public TemplateSubstitution getImplicitOutputs(AttributeMap map)
        throws EvalException, InterruptedException {
      ImmutableMap<String, TemplateSubstitution> outputs = calculateOutputs(map);
      ImmutableList.Builder<String> builder = new ImmutableList.Builder<>();
      for (Map.Entry<String, TemplateSubstitution> entry : outputs.entrySet()) {
        if (entry.getValue().isPlural()) {
          builder.addAll(entry.getValue().plural());
        } else {
          builder.add(entry.getValue().singular());
        }
      }
      TemplateSubstitution implicitOutputs = new TemplateSubstitution();
      implicitOutputs.setPlural(builder.build());
      return implicitOutputs;
    }
  }

  /**
   * Implicit output functions executing Skylark code.
   */
  public static final class SkylarkImplicitOutputsFunctionWithCallback
      extends SkylarkImplicitOutputsFunction {

    private final SkylarkCallbackFunction callback;
    private final Location loc;

    public SkylarkImplicitOutputsFunctionWithCallback(
        SkylarkCallbackFunction callback, Location loc) {
      this.callback = callback;
      this.loc = loc;
    }

    @Override
    public ImmutableMap<String, TemplateSubstitution> calculateOutputs(AttributeMap map)
        throws EvalException, InterruptedException {
      Map<String, Object> attrValues = new HashMap<>();
      for (String attrName : map.getAttributeNames()) {
        Type<?> attrType = map.getAttributeType(attrName);
        // Don't include configurable attributes: we don't know which value they might take
        // since we don't yet have a build configuration.
        if (!map.isConfigurable(attrName)) {
          Object value = map.get(attrName, attrType);
          attrValues.put(attrName, value == null ? Runtime.NONE : value);
        }
      }
      ClassObject attrs = NativeClassObjectConstructor.STRUCT.create(
          attrValues,
          "Attribute '%s' either doesn't exist "
          + "or uses a select() (i.e. could have multiple values)");
      try {
        ImmutableMap.Builder<String, TemplateSubstitution> builder = ImmutableMap.builder();
        for (Map.Entry<String, String> entry : castMap(callback.call(attrs),
            String.class, String.class, "implicit outputs function return value").entrySet()) {

          TemplateSubstitution substitution = fromTemplates(entry.getValue()).getImplicitOutputs(map);
          if (substitution.isEmpty()) {
            throw new EvalException(
                loc,
                String.format(
                    "For attribute '%s' in outputs: %s",
                    entry.getKey(), "Invalid placeholder(s) in template"));
          }

          builder.put(entry.getKey(), substitution);
        }
        return builder.build();
      } catch (IllegalArgumentException e) {
        throw new EvalException(loc, e.getMessage());
      }
    }
  }

  /**
   * Implicit output functions using a simple an output map.
   */
  public static final class SkylarkImplicitOutputsFunctionWithMap
      extends SkylarkImplicitOutputsFunction {

    private final ImmutableMap<String, String> outputMap;

    public SkylarkImplicitOutputsFunctionWithMap(ImmutableMap<String, String> outputMap) {
      this.outputMap = outputMap;
    }

    @Override
    public ImmutableMap<String, TemplateSubstitution> calculateOutputs(AttributeMap map) throws EvalException {

      ImmutableMap.Builder<String, TemplateSubstitution> builder = ImmutableMap.builder();
      for (Map.Entry<String, String> entry : outputMap.entrySet()) {
        // Empty iff invalid placeholders present.
        TemplateSubstitution substitution = fromTemplates(entry.getValue()).getImplicitOutputs(map);
        if (substitution.isEmpty()) {
          throw new EvalException(
              null,
              String.format(
                  "For attribute '%s' in outputs: %s",
                  entry.getKey(), "Invalid placeholder(s) in template"));

        }

        builder.put(entry.getKey(), substitution);
      }
      return builder.build();
    }
  }

  /**
   * Implicit output functions which can not throw an EvalException.
   */
  public abstract static class SafeImplicitOutputsFunction extends ImplicitOutputsFunction {
    @Override
    public abstract TemplateSubstitution getImplicitOutputs(AttributeMap map); /* guaranteed to output Iterable<String> */
  }

  /**
   * An interface to objects that can retrieve rule attributes.
   */
  public interface AttributeValueGetter {
    /**
     * Returns the value(s) of attribute "attr" in "rule", or empty set if attribute unknown.
     */
    Object get(AttributeMap rule, String attr);
  }

  /**
   * The default rule attribute retriever.
   *
   * <p>Custom {@link AttributeValueGetter} implementations may delegate to this object as a
   * fallback mechanism.
   */
  public static final AttributeValueGetter DEFAULT_RULE_ATTRIBUTE_GETTER =
      new AttributeValueGetter() {
        @Override
        public Object get(AttributeMap rule, String attr) {
          return attributeValues(rule, attr);
        }
      };

  private static final Escaper PERCENT_ESCAPER = Escapers.builder().addEscape('%', "%%").build();

  /**
   * Given a newly-constructed Rule instance (with attributes populated),
   * returns the list of output files that this rule produces implicitly.
   */
  public abstract TemplateSubstitution getImplicitOutputs(AttributeMap rule)
      throws EvalException, InterruptedException;

  /**
   * The implicit output function that returns no files.
   */
  public static final SafeImplicitOutputsFunction NONE = new SafeImplicitOutputsFunction() {
      @Override public TemplateSubstitution getImplicitOutputs(AttributeMap rule) {
        return new TemplateSubstitution();
      }
    };

  /**
   * A convenience wrapper for {@link #fromTemplates(Iterable)}.
   */
  public static SafeImplicitOutputsFunction fromTemplates(String... templates) {
    return fromTemplates(Arrays.asList(templates));
  }

  public static final class TemplateSubstitution {
    public TemplateSubstitution() {
      plural = true;
      pluralValue = ImmutableList.<String>of();
    }

    public void setSingular( String value ) {
      plural = false;
      singularValue = value;
    }

    public void setPlural( List<String> value ) {
      plural = true;
      pluralValue = value;
    }

    public boolean isPlural() {
      return plural;
    }

    public boolean isEmpty() {
      return plural && pluralValue.isEmpty();
    }

    public List<String> plural() {
      return plural ? pluralValue : null;
    }

    public String singular() {
      return plural ? null : singularValue;
    }

    private boolean plural;
    private List<String> pluralValue;
    private String singularValue;
  };

  /**
   * The implicit output function that generates files based on a set of
   * template substitutions using rule attribute values.
   *
   * @param templates The templates used to construct the name of the implicit
   *   output file target.  The substring "%{name}" will be replaced by the
   *   actual name of the rule, the substring "%{srcs}" will be replaced by the
   *   name of each source file without its extension.  If multiple %{}
   *   substrings exist, the cross-product of them is generated.
   */
  public static SafeImplicitOutputsFunction fromTemplates(final Iterable<String> templates) {
    return new SafeImplicitOutputsFunction() {
      // TODO(bazel-team): parse the templates already here
      @Override
      public TemplateSubstitution getImplicitOutputs(AttributeMap rule) {
        TemplateSubstitution result = null;
        Iterable<String> knownPlural = null;
        int templateCount = 0;
        for (String template : templates) {
          TemplateSubstitution substitution = substitutePlaceholderIntoTemplate(template, rule);
          templateCount++;
          if (substitution.isEmpty()) {
            continue;
          }
          if (result == null) {
            result = substitution;
          } else {
            /* original result could be singular */
            if (!result.isPlural()) {
              String singular = result.singular();
              result = new TemplateSubstitution();
              result.setPlural(ImmutableList.<String>of(singular));
            }
            /* new result can be singular */
            ImmutableList.Builder<String> builder = new ImmutableList.Builder<>();
            builder.addAll(result.plural());
            if (substitution.isPlural()) {
              builder.addAll(substitution.plural());
            } else {
              builder.add(substitution.singular());
            }
            result.setPlural(builder.build());
          }
        }
        if (result == null) {
          result = new TemplateSubstitution();
        }
        return result;
      }

      @Override
      public String toString() {
        return StringUtil.joinEnglishList(templates);
      }
    };
  }

  /**
   * A convenience wrapper for {@link #fromFunctions(Iterable)}.
   */
  public static SafeImplicitOutputsFunction fromFunctions(
      SafeImplicitOutputsFunction... functions) {
    return fromFunctions(Arrays.asList(functions));
  }

  /**
   * The implicit output function that generates files based on a set of
   * template substitutions using rule attribute values.
   *
   * @param functions The functions used to construct the name of the implicit
   *   output file target.  The substring "%{name}" will be replaced by the
   *   actual name of the rule, the substring "%{srcs}" will be replaced by the
   *   name of each source file without its extension.  If multiple %{}
   *   substrings exist, the cross-product of them is generated.
   */
  public static SafeImplicitOutputsFunction fromFunctions(
      final Iterable<SafeImplicitOutputsFunction> functions) {
    return new SafeImplicitOutputsFunction() {
      @Override
      public TemplateSubstitution getImplicitOutputs(AttributeMap rule) {
        ImmutableList.Builder<String> builder = new ImmutableList.Builder<>();
        int functionCount = 0;
        boolean singular = true;
        for (SafeImplicitOutputsFunction function : functions) {
          TemplateSubstitution implicitOutputs = function.getImplicitOutputs(rule);
          if (implicitOutputs.isPlural()) {
            builder.addAll(implicitOutputs.plural());
            singular = false;
          } else {
            builder.add(implicitOutputs.singular());
          }
          functionCount++;
        }
        TemplateSubstitution substitution = new TemplateSubstitution();
        List<String> result = builder.build();
        if (functionCount == 1 && singular) {
          substitution.setSingular(Iterables.getOnlyElement(result));
        } else {
          substitution.setPlural(result);
        }
        return substitution;
      }
      @Override
      public String toString() {
        return StringUtil.joinEnglishList(functions);
      }
    };
  }

  /**
   * Coerces attribute "attrName" of the specified rule into a sequence of
   * strings.  Helper function for {@link #fromTemplates(Iterable)}.
   */
  private static Object attributeValues(AttributeMap rule, String attrName) {
    if (attrName.equals("dirname")) {
      PathFragment dir = PathFragment.create(rule.getName()).getParentDirectory();
      return (dir.segmentCount() == 0) ? "" : (dir.getPathString() + "/");
    } else if (attrName.equals("basename")) {
      return PathFragment.create(rule.getName()).getBaseName();
    }

    Type<?> attrType = rule.getAttributeType(attrName);
    if (attrType == null) {
      return Collections.emptySet();
    }
    // String attributes and lists are easy.
    if (Type.STRING == attrType) {
      return rule.get(attrName, Type.STRING);
    } else if (Type.STRING_LIST == attrType) {
      return Sets.newLinkedHashSet(rule.get(attrName, Type.STRING_LIST));
    } else if (BuildType.LABEL == attrType) {
      // Labels are most often used to change the extension,
      // e.g. %.foo -> %.java, so we return the basename w/o extension.
      Label label = rule.get(attrName, BuildType.LABEL);
      return FileSystemUtils.removeExtension(label.getName());
    } else if (BuildType.LABEL_LIST == attrType) {
      // Labels are most often used to change the extension,
      // e.g. %.foo -> %.java, so we return the basename w/o extension.
      return Sets.newLinkedHashSet(
          Iterables.transform(rule.get(attrName, BuildType.LABEL_LIST),
              new Function<Label, String>() {
                @Override
                public String apply(Label label) {
                  return FileSystemUtils.removeExtension(label.getName());
                }
              }));
    } else if (BuildType.OUTPUT == attrType) {
      Label out = rule.get(attrName, BuildType.OUTPUT);
      return out.getName();
    } else if (BuildType.OUTPUT_LIST == attrType) {
      return Sets.newLinkedHashSet(
          Iterables.transform(rule.get(attrName, BuildType.OUTPUT_LIST),
              new Function<Label, String>() {
                @Override
                public String apply(Label label) {
                  return label.getName();
                }
              }));
    }
    throw new IllegalArgumentException(
        "Don't know how to handle " + attrName + " : " + attrType);
  }

  /**
   * Collects all named placeholders from the template while replacing them with %s.
   *
   * <p>Example: for {@code template} "%{name}_%{locales}.foo", it will return "%s_%s.foo" and
   * store "name" and "locales" in {@code placeholders}.
   *
   * <p>Incomplete placeholders are treated like text: for "a-%{x}-%{y" this method returns
   * "a-%s-%%{y" and stores "x" in {@code placeholders}.
   *
   * @param template a string with placeholders of the format %{...}
   * @param placeholders a collection to collect placeholders into; may contain duplicates if not a
   *     Set
   * @return a format string for {@link String#format}, created from the template string with every
   *     placeholder replaced by %s
   */
  public static String createPlaceholderSubstitutionFormatString(String template,
      Collection<String> placeholders) {
    return createPlaceholderSubstitutionFormatStringRecursive(template, placeholders,
        new StringBuilder());
  }

  private static String createPlaceholderSubstitutionFormatStringRecursive(String template,
      Collection<String> placeholders, StringBuilder formatBuilder) {
    int start = template.indexOf("%{");
    if (start < 0) {
      return formatBuilder.append(PERCENT_ESCAPER.escape(template)).toString();
    }

    int end = template.indexOf('}', start + 2);
    if (end < 0) {
      return formatBuilder.append(PERCENT_ESCAPER.escape(template)).toString();
    }

    formatBuilder.append(PERCENT_ESCAPER.escape(template.substring(0, start))).append("%s");
    placeholders.add(template.substring(start + 2, end));
    return createPlaceholderSubstitutionFormatStringRecursive(template.substring(end + 1),
        placeholders, formatBuilder);
  }

  /**
   * Given a template string, replaces all placeholders of the form %{...} with
   * the values from attributeSource.  If there are multiple placeholders, then
   * the output is the cross product of substitutions.
   */
  public static TemplateSubstitution substitutePlaceholderIntoTemplate(String template,
      AttributeMap rule) {
    return substitutePlaceholderIntoTemplate(template, rule, DEFAULT_RULE_ATTRIBUTE_GETTER, null);
  }

  /**
   * Substitutes attribute-placeholders in a template string, producing all possible combinations.
   *
   * @param template the template string, may contain named placeholders for rule attributes, like
   *     <code>%{name}</code> or <code>%{deps}</code>
   * @param rule the rule whose attributes the placeholders correspond to
   * @param placeholdersInTemplate if specified, will contain all placeholders found in the
   *     template; may contain duplicates
   * @return all possible combinations of the attributes referenced by the placeholders,
   *     substituted into the template; empty if any of the placeholders expands to no values
   */
  public static TemplateSubstitution substitutePlaceholderIntoTemplate(String template,
      AttributeMap rule, AttributeValueGetter attributeGetter,
      @Nullable List<String> placeholdersInTemplate) {
    TemplateSubstitution substitution = new TemplateSubstitution();
    List<String> placeholders = (placeholdersInTemplate == null)
        ? Lists.<String>newArrayList()
        : placeholdersInTemplate;
    String formatStr = createPlaceholderSubstitutionFormatString(template, placeholders);
    if (placeholders.isEmpty()) {
      substitution.setSingular(template);
      return substitution; /* singular string */
    }

    List<Set<String>> values = Lists.newArrayListWithCapacity(placeholders.size());
    boolean singular = true;
    for (String placeholder : placeholders) {
      Object attrValue = attributeGetter.get(rule, placeholder);
      Set<String> attrValues;
      if (attrValue instanceof Set) {
        attrValues = (Set<String>) attrValue;
        if (attrValues.isEmpty()) {
          return substitution;
        }
        singular = false;
      } else {
        attrValues = singleton(attrValue.toString());
      }
      values.add(attrValues);
    }
    if (singular) {
      substitution.setSingular(
          String.format(formatStr, Iterables.getOnlyElement(Sets.cartesianProduct(values)).toArray()));
      return substitution;
    }
    ImmutableList.Builder<String> out = new ImmutableList.Builder<>();
    for (List<String> combination : Sets.cartesianProduct(values)) {
      out.add(String.format(formatStr, combination.toArray()));
    }
    substitution.setPlural(out.build());
    return substitution;
  }
}
