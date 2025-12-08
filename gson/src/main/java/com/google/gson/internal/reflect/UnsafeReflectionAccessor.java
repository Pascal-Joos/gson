/*
 * Copyright (C) 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.gson.internal.reflect;

import com.google.gson.JsonIOException;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.annotation.Nullable;

/**
 * ReflectionAccessor implementation based on {@code sun.misc.Unsafe}.
 *
 * <p>This implementation falls back to {@link PreJava9ReflectionAccessor} if {@code Unsafe} is not
 * available.
 */
public final class UnsafeReflectionAccessor extends ReflectionAccessor {

  private static final ReflectionAccessor instance = new UnsafeReflectionAccessor();

  public static ReflectionAccessor getInstance() {
    return instance;
  }

  private static @Nullable Class<?> unsafeClass = null;
  private static @Nullable Object theUnsafe;
  private static @Nullable Field overrideField;

  static {
    theUnsafe = getUnsafeInstance();
    overrideField = getOverrideField();
  }

  @Override
  public void makeAccessible(AccessibleObject ao) {
    boolean success = makeAccessibleWithUnsafe(ao);
    if (!success) {
      try {
        // unsafe couldn't be found, so try using accessible anyway
        ao.setAccessible(true);
      } catch (SecurityException e) {
        throw new JsonIOException(
            "Gson couldn't modify fields for "
                + ao
                + "\nand sun.misc.Unsafe not found.\nEither write a custom type adapter,"
                + " or make fields accessible, or include sun.misc.Unsafe.",
            e);
      }
    }
  }

  // Visible for testing only
  boolean makeAccessibleWithUnsafe(AccessibleObject ao) {
    if (theUnsafe != null && unsafeClass != null && overrideField != null) {
      try {
        Method method = unsafeClass.getMethod("objectFieldOffset", Field.class);
        long overrideOffset = (Long) method.invoke(theUnsafe, overrideField);
        Method putBooleanMethod =
            unsafeClass.getMethod("putBoolean", Object.class, long.class, boolean.class);
        putBooleanMethod.invoke(theUnsafe, ao, overrideOffset, true);
        return true;
      } catch (Exception ignored) {
        // do nothing
      }
    }
    return false;
  }

  private static @Nullable Object getUnsafeInstance() {
    try {
      unsafeClass = Class.forName("sun.misc.Unsafe");
      Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
      unsafeField.setAccessible(true);
      return unsafeField.get(null);
    } catch (Exception e) {
      return null;
    }
  }

  private static @Nullable Field getOverrideField() {
    try {
      return AccessibleObject.class.getDeclaredField("override");
    } catch (NoSuchFieldException e) {
      return null;
    }
  }
}
