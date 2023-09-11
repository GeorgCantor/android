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
package com.android.tools.idea.insights.events

import com.android.tools.idea.insights.AppInsightsState
import com.android.tools.idea.insights.CONNECTION1
import com.android.tools.idea.insights.ISSUE1
import com.android.tools.idea.insights.ISSUE2
import com.android.tools.idea.insights.LoadingState
import com.android.tools.idea.insights.Selection
import com.android.tools.idea.insights.TEST_FILTERS
import com.android.tools.idea.insights.Timed
import com.android.tools.idea.insights.analytics.TestAppInsightsTracker
import com.android.tools.idea.insights.events.actions.Action
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

class SelectedIssueChangedTest {

  @Test
  fun `selecting a different issue causes selection to update and actions to dispatch`() {
    val currentState =
      AppInsightsState(
        Selection(CONNECTION1, listOf(CONNECTION1)),
        TEST_FILTERS,
        LoadingState.Ready(Timed(Selection(ISSUE1, listOf(ISSUE1, ISSUE2)), Instant.now()))
      )

    val transition = SelectedIssueChanged(ISSUE2).transition(currentState, TestAppInsightsTracker)

    with(transition) {
      assertThat((transition.newState.issues as LoadingState.Ready).value.value)
        .isEqualTo(Selection(ISSUE2, listOf(ISSUE1, ISSUE2)))
      assertThat(transition.newState.currentIssueVariants)
        .isInstanceOf(LoadingState.Loading::class.java)
      assertThat(transition.newState.currentIssueDetails)
        .isInstanceOf(LoadingState.Loading::class.java)
      assertThat(transition.newState.currentNotes).isInstanceOf(LoadingState.Loading::class.java)

      assertThat((action as Action.Multiple).actions)
        .containsExactly(
          Action.FetchIssueVariants(ISSUE2.id),
          Action.FetchDetails(ISSUE2.id),
          Action.FetchNotes(ISSUE2.id)
        )
    }
  }

  @Test
  fun `selecting the same issue causes no-op`() {
    val currentState =
      AppInsightsState(
        Selection(CONNECTION1, listOf(CONNECTION1)),
        TEST_FILTERS,
        LoadingState.Ready(Timed(Selection(ISSUE1, listOf(ISSUE1, ISSUE2)), Instant.now()))
      )

    val transition = SelectedIssueChanged(ISSUE1).transition(currentState, TestAppInsightsTracker)

    assertThat(transition).isEqualTo(StateTransition(currentState, Action.NONE))
  }
}
