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
package sample.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.Dropdown
import org.jetbrains.jewel.GroupHeader
import org.jetbrains.jewel.LocalResourceLoader
import org.jetbrains.jewel.Outline
import org.jetbrains.jewel.Text
import org.jetbrains.jewel.separator

/**
 * This sample composable showcasing the different dropdown components was adapted from the public Jewel repository standalone sample.
 * See: https://github.com/JetBrains/jewel
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Dropdowns() {
  GroupHeader("Dropdowns")
  Row(
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val items = remember {
      listOf(
        "Light",
        "Dark",
        "---",
        "High Contrast",
        "Darcula",
        "IntelliJ Light",
      )
    }
    var selected by remember { mutableStateOf(items.first()) }
    val resourceLoader = LocalResourceLoader.current

    Dropdown(
      enabled = false,
      resourceLoader = resourceLoader,
      menuContent = {
      },
    ) {
      Text(selected)
    }
    Dropdown(
      resourceLoader = resourceLoader,
      menuContent = {
        items.forEach {
          if (it == "---") {
            separator()
          }
          else {
            selectableItem(selected == it, {
              selected = it
            }) {
              Text(it)
            }
          }
        }
        separator()
        submenu(submenu = {
          items.forEach {
            if (it == "---") {
              separator()
            }
            else {
              selectableItem(selected == it, {
                selected = it
              }) {
                Text(it)
              }
            }
          }
          separator()
          submenu(submenu = {
            items.forEach {
              if (it == "---") {
                separator()
              }
              else {
                selectableItem(selected == it, {
                  selected = it
                }) {
                  Text(it)
                }
              }
            }
          }) {
            Text("Submenu2")
          }
        }) {
          Text("Submenu")
        }
      },
    ) {
      Text(selected)
    }
    Dropdown(
      resourceLoader = resourceLoader,
      outline = Outline.Error,
      menuContent = {
        items.forEach {
          if (it == "---") {
            separator()
          }
          else {
            selectableItem(selected == it, {
              selected = it
            }) {
              Text(it)
            }
          }
        }
      },
    ) {
      Text(selected)
    }
  }
}