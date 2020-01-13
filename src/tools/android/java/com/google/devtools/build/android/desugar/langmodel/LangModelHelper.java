/*
 * Copyright 2019 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.build.android.desugar.langmodel;

import static com.google.devtools.build.android.desugar.langmodel.LangModelConstants.NEST_COMPANION_CLASS_SIMPLE_NAME;

import com.google.common.collect.ImmutableMap;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** A utility class for the desguaring of nest-based access control classes. */
public final class LangModelHelper {

  /**
   * The primitive type as specified at
   * https://docs.oracle.com/javase/specs/jvms/se11/html/jvms-2.html#jvms-2.3
   */
  private static final ImmutableMap<Type, Type> PRIMITIVES_TO_BOXED_TYPES =
      ImmutableMap.<Type, Type>builder()
          .put(Type.INT_TYPE, Type.getObjectType("java/lang/Integer"))
          .put(Type.BOOLEAN_TYPE, Type.getObjectType("java/lang/Boolean"))
          .put(Type.BYTE_TYPE, Type.getObjectType("java/lang/Byte"))
          .put(Type.CHAR_TYPE, Type.getObjectType("java/lang/Character"))
          .put(Type.SHORT_TYPE, Type.getObjectType("java/lang/Short"))
          .put(Type.DOUBLE_TYPE, Type.getObjectType("java/lang/Double"))
          .put(Type.FLOAT_TYPE, Type.getObjectType("java/lang/Float"))
          .put(Type.LONG_TYPE, Type.getObjectType("java/lang/Long"))
          .build();

  /**
   * Returns the internal name of the nest host class for a given class.
   *
   * <p>e.g. The nest host of a/b/C$D is a/b/C
   */
  public static String nestHost(String classInternalName) {
    int index = classInternalName.indexOf('$');
    return index > 0 ? classInternalName.substring(0, index) : classInternalName;
  }

  /**
   * Returns the internal name of the nest companion class for a given class.
   *
   * <p>e.g. The nest host of a/b/C$D is a/b/C$NestCC
   */
  public static String nestCompanion(String classInternalName) {
    return nestHost(classInternalName) + '$' + NEST_COMPANION_CLASS_SIMPLE_NAME;
  }

  /** Whether the given type is a primitive type */
  public static boolean isPrimitive(Type type) {
    return PRIMITIVES_TO_BOXED_TYPES.containsKey(type);
  }

  public static Type toBoxedType(Type primitiveType) {
    return PRIMITIVES_TO_BOXED_TYPES.get(primitiveType);
  }

  /**
   * Adjust the operation code for dup and pop operations by type size. The method supplements
   * {@link org.objectweb.asm.Type#getOpcode(int)}.
   */
  public static int getTypeSizeAlignedOpcode(int baseOpcode, Type type) {
    if (baseOpcode == Opcodes.DUP || baseOpcode == Opcodes.DUP2) {
      switch (type.getSize()) {
        case 1:
          return Opcodes.DUP;
        case 2:
          return Opcodes.DUP2;
        default:
          // fall out: Impossible Condition
          throw new AssertionError(
              String.format("Impossible size (%d) for type (%s)", type.getSize(), type));
      }
    } else if (baseOpcode == Opcodes.DUP_X1 || baseOpcode == Opcodes.DUP_X2) {
      switch (type.getSize()) {
        case 1:
          return Opcodes.DUP_X1;
        case 2:
          return Opcodes.DUP_X2;
        default:
          // fall out: Impossible Condition
          throw new AssertionError(
              String.format("Impossible size (%d) for type (%s)", type.getSize(), type));
      }

    } else if (baseOpcode == Opcodes.DUP2_X1 || baseOpcode == Opcodes.DUP2_X2) {
      switch (type.getSize()) {
        case 1:
          return Opcodes.DUP2_X1;
        case 2:
          return Opcodes.DUP2_X2;
        default:
          // fall out: Impossible Condition
          throw new AssertionError(
              String.format("Impossible size (%d) for type (%s)", type.getSize(), type));
      }
    } else if (baseOpcode == Opcodes.POP || baseOpcode == Opcodes.POP2) {
      switch (type.getSize()) {
        case 1:
          return Opcodes.POP;
        case 2:
          return Opcodes.POP2;
        default:
          // fall out: Impossible Condition
          throw new AssertionError(
              String.format("Impossible size (%d) for type (%s)", type.getSize(), type));
      }
    } else {
      throw new UnsupportedOperationException(
          String.format(
              "Unsupported Opcode Adjustment: Type: %s of base opcode: %d", type, baseOpcode));
    }
  }

  /**
   * A checker on whether the give class is eligible as an inner class by its class internal name.
   *
   * <p>Note: The reliable source of truth is to check the InnerClasses attribute. However, the
   * attribute may have not been visited yet.
   */
  public static boolean isEligibleAsInnerClass(String className) {
    return className.contains("$");
  }

  /**
   * Whether the referenced class member is a in-nest distinct class access within the given
   * enclosing method.
   */
  public static boolean isCrossMateRefInNest(
      ClassMemberKey referencedMember, MethodKey enclosingMethod) {
    String enclosingClassName = enclosingMethod.owner();
    String referencedMemberName = referencedMember.owner();
    return (isEligibleAsInnerClass(enclosingClassName)
            || isEligibleAsInnerClass(referencedMemberName))
        && !referencedMemberName.equals(enclosingClassName);
  }

  /** Emits efficient instructions for a given integer push operation. */
  public static void visitPushInstr(MethodVisitor mv, final int value) {
    if (value >= -1 && value <= 5) {
      mv.visitInsn(Opcodes.ICONST_0 + value);
    } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
      mv.visitIntInsn(Opcodes.BIPUSH, value);
    } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
      mv.visitIntInsn(Opcodes.SIPUSH, value);
    } else {
      mv.visitLdcInsn(value);
    }
  }

  private LangModelHelper() {}
}
