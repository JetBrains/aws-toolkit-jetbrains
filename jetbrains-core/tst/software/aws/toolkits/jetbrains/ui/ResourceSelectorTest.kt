// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.ui

import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.runInEdtAndWait
import com.nhaarman.mockitokotlin2.mock
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import software.aws.toolkits.jetbrains.core.MockResourceCache
import software.aws.toolkits.jetbrains.core.Resource
import software.aws.toolkits.jetbrains.utils.test.retryableAssert
import software.aws.toolkits.resources.message
import java.util.concurrent.CompletableFuture

class ResourceSelectorTest {
    @Rule
    @JvmField
    val projectRule = ProjectRule()

    private val mockResource = mock<Resource.Cached<List<String>>> {
        on { id }.thenReturn("mockResource")
    }

    private val mockResourceCache = MockResourceCache.getInstance(projectRule.project)

    @Test
    fun canSpecifyADefaultItem() {
        mockResourceCache.addEntry(mockResource, listOf("foo", "bar", "baz"))
        val comboBox = ResourceSelector(projectRule.project, mockResource)

        comboBox.selectedItem = "bar"

        retryableAssert {
            runInEdtAndWait {
                assertThat(comboBox.selected()).isEqualTo("bar")
            }
        }
    }

    @Test
    fun canSpecifyADefaultItemMatcher() {
        mockResourceCache.addEntry(mockResource, listOf("foo", "bar", "baz"))
        val comboBox = ResourceSelector(projectRule.project, mockResource)

        comboBox.reload()
        comboBox.selectedItem { it.endsWith("z") }

        retryableAssert {
            runInEdtAndWait {
                assertThat(comboBox.selected()).isEqualTo("baz")
            }
        }
    }

    @Test
    fun loadFailureShowsAnErrorAndDisablesTheBox() {
        val future = CompletableFuture<List<String>>()
        mockResourceCache.addEntry(mockResource, future)
        val comboBox = ResourceSelector(projectRule.project, mockResource)

        future.completeExceptionally(RuntimeException("boom"))

        retryableAssert {
            runInEdtAndWait {
                assertThat(comboBox.isEnabled).isFalse()
                assertThat(comboBox.model.selectedItem).isEqualTo(message("loading_resource.failed"))
            }
        }
    }

    @Test
    fun usePreviouslySelectedItemAfterReloadUnlessSelectItemSet() {
        mockResourceCache.addEntry(mockResource, listOf("foo", "bar", "baz"))
        val comboBox = ResourceSelector(projectRule.project, mockResource)

        runInEdtAndWait {
            comboBox.selectedItem = "bar"
        }

        comboBox.reload()

        retryableAssert {
            runInEdtAndWait {
                assertThat(comboBox.selected()).isEqualTo("bar")
            }
        }
    }

    @Test
    fun comboBoxIsDisabledWhileEntriesAreLoading() {
        val future = CompletableFuture<List<String>>()
        mockResourceCache.addEntry(mockResource, future)
        val comboBox = ResourceSelector(projectRule.project, mockResource)

        assertThat(comboBox.selected()).isNull()

        comboBox.reload()
        runInEdtAndWait {
            assertThat(comboBox.isEnabled).isFalse()
            assertThat(comboBox.selectedItem).isEqualTo(message("loading_resource.loading"))
        }

        future.complete(listOf("foo", "bar", "baz"))
        retryableAssert {
            runInEdtAndWait {
                assertThat(comboBox.isEnabled).isTrue()
                assertThat(comboBox.selectedItem).isNull()
            }
        }
    }

    @Test
    fun actionListenerIsInvokedOnLoadingCorrectly() {
        val future = CompletableFuture<List<String>>() // Use the future to force slow load
        mockResourceCache.addEntry(mockResource, future)
        val comboBox = ResourceSelector(projectRule.project, mockResource, loadOnCreate = false)

        val loadingStatus = mutableListOf<Boolean>()
        comboBox.addActionListener { loadingStatus.add(comboBox.isLoading) }

        comboBox.reload()

        runInEdtAndWait {
            comboBox.selectedItem = "bar"
        }

        future.complete(listOf("foo", "bar", "baz"))

        retryableAssert {
            runInEdtAndWait {
                assertThat(comboBox.selected()).isEqualTo("bar")
                assertThat(loadingStatus).containsExactly(true, false)
            }
        }
    }

    @Test
    fun aSingleResultWillAutoSelect() {
        mockResourceCache.addEntry(mockResource, listOf("bar"))
        val comboBox = ResourceSelector(projectRule.project, mockResource)

        runInEdtAndWait {
            comboBox.selectedItem = "bar"
        }

        comboBox.reload()

        retryableAssert {
            runInEdtAndWait {
                assertThat(comboBox.selected()).isEqualTo("bar")
            }
        }
    }

    @Test
    fun onlyTheLastReloadIsApplied() {
        fun createMockResource(name: String): Triple<Resource.Cached<List<String>>, CompletableFuture<List<String>>, List<String>> {
            val future = CompletableFuture<List<String>>()
            val resource = mock<Resource.Cached<List<String>>> {
                on { id }.thenReturn(name)
            }

            mockResourceCache.addEntry(resource, future)

            return Triple(resource, future, listOf(name))
        }

        // Create a bunch of different cache entries
        val resultList = listOf(
            createMockResource("1"),
            createMockResource("2"),
            createMockResource("3"),
            createMockResource("4"),
            createMockResource("5")
        )

        var counter = 0
        val comboBox = ResourceSelector(projectRule.project, { resultList[counter].first }, loadOnCreate = false)

        val loadingStatus = mutableListOf<Boolean>()
        comboBox.addActionListener { loadingStatus.add(comboBox.isLoading) }

        // Trigger a reload for each resource to simulate a bunch of reloads against different cache entries
        runInEdtAndWait {
            resultList.forEach { _ ->
                comboBox.reload()
                counter++
            }
        }

        // Complete the cache load
        resultList.forEach {
            it.second.complete(it.third)
        }

        retryableAssert {
            runInEdtAndWait {
                assertThat(comboBox.selected()).isEqualTo("5")
                // 5 loads = 5 loadings + 1 loaded
                assertThat(loadingStatus).containsExactly(true, true, true, true, true, false)
            }
        }
    }
}
