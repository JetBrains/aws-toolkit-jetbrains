// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.core.notification

import com.intellij.testFramework.ProjectRule
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class NoticeManagerTest {
    @Rule
    @JvmField
    val projectRule = ProjectRule()

    private lateinit var sut: DefaultNoticeManager

    @Before
    fun setupTest() {
        sut = DefaultNoticeManager()
    }

    @Test
    fun noticeDoesNotRequireNotification() {
        val notice = mock<NoticeType>()
        whenever(notice.isNotificationRequired()).thenReturn(false)

        val notices = sut.getRequiredNotices(listOf(notice), projectRule.project)

        assertThat(notices).isEmpty()
    }

    @Test
    fun noticeRequiresNotification() {
        val notice = createSampleNotice(true, false)

        val notices = sut.getRequiredNotices(listOf(notice), projectRule.project)

        assertThat(notices).hasSize(1)
        assertThat(notices).contains(notice)
    }

    @Test
    fun nonSerializedNoticeCallsIsNotificationSuppressed() {
        val notice = spy(createSampleNotice(requiresNotification = true, isNotificationSuppressed = false))

        val notices = sut.getRequiredNotices(listOf(notice), projectRule.project)

        assertThat(notices).hasSize(1)
        assertThat(notices).contains(notice)

        verify(notice).isNotificationSuppressed(null)
    }

    @Test
    fun suppressedNoticeDoesNotRequireNotification() {
        val notice = createSampleNotice(true, true)

        sut.loadState(NoticeStateList(listOf(NoticeState(notice.id, notice.getSuppressNotificationValue()))))
        val notices = sut.getRequiredNotices(listOf(notice), projectRule.project)

        assertThat(notices).isEmpty()
    }

    @Test
    fun suppressingNoticeDoesNotRequireNotification() {
        val suppressionValue = "version10"
        val notice = mock<NoticeType>()
        whenever(notice.getSuppressNotificationValue()).thenReturn(suppressionValue)
        whenever(notice.isNotificationRequired()).thenReturn(true)
        whenever(notice.isNotificationSuppressed(eq(suppressionValue))).thenReturn(true)

        sut.suppressNotification(notice)
        val notices = sut.getRequiredNotices(listOf(notice), projectRule.project)

        assertThat(notices).isEmpty()
        verify(notice, times(1)).isNotificationSuppressed(eq(suppressionValue))
    }

    @Test
    fun resettingSuppressionsRequireNotification() {
        val notice = createSampleNotice(true, false)

        sut.suppressNotification(notice)
        sut.resetAllNotifications()
        val notices = sut.getRequiredNotices(listOf(notice), projectRule.project)

        assertThat(notices).hasSize(1)
        assertThat(notices).contains(notice)
    }

    private fun createSampleNotice(requiresNotification: Boolean, isNotificationSuppressed: Boolean): NoticeType = object : NoticeType {
        override val id: String = "noticeId"
        override fun getSuppressNotificationValue(): String = "noticeValue"
        override fun isNotificationSuppressed(previousSuppressNotificationValue: String?): Boolean = isNotificationSuppressed
        override fun isNotificationRequired(): Boolean = requiresNotification
        override fun getNoticeContents(): NoticeContents = NoticeContents("title", "message")
    }
}
