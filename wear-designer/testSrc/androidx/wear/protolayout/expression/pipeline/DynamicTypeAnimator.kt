/*
 * Copyright (C) 2024 The Android Open Source Project
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
package androidx.wear.protolayout.expression.pipeline

import com.android.annotations.NonNull
import org.jetbrains.android.dom.animator.PropertyValuesHolder

/** Interface that should match the one from the AndroidX library */
interface DynamicTypeAnimator {

  interface TypeEvaluator<T> {
    fun evaluate(fraction: Float, startValue: T, endValue: T): T
  }

  val typeEvaluator: TypeEvaluator<*>

  /**
   * Sets the float values that this animation will animate between.
   *
   * @param values The float values to animate between.
   */
  fun setFloatValues(@NonNull vararg values: Float)

  /**
   * Sets the integer values that this animation will animate between.
   *
   * @param values The integer values to animate between.
   */
  fun setIntValues(@NonNull vararg values: Int)

  /**
   * Advances the animation to the specified time frame.
   *
   * @param newTime The new time in milliseconds.
   */
  fun setAnimationFrameTime(newTime: Long)

  val propertyValuesHolders: Array<PropertyValuesHolder?>?

  val lastAnimatedValue: Any?

  /**
   * Gets the duration of the animation, in milliseconds.
   *
   * @return The duration of the animation.
   */
  val duration: Long

  /**
   * Gets the start delay of the animation, in milliseconds.
   *
   * @return The start delay of the animation.
   */
  val startDelay: Long
}
