/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.testutils

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import kotlin.reflect.KMutableProperty

/** Sets this property to [value] and restores the original value when [disposable] is disposed. */
fun <T> KMutableProperty<T>.override(value: T, disposable: Disposable) {
    val oldValue = getter.call()
    Disposer.register(disposable) {
        setter.call(oldValue)
    }
    setter.call(value)
}
