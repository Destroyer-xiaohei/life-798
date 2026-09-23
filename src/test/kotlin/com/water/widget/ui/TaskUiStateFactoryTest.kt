package com.water.widget.ui

import com.water.widget.Account
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskUiStateFactoryTest {
    @Test
    fun `统计可运行账号和双平台账号`() {
        val a = Account("账号A").apply { token = "token"; appToken = "app" }
        val b = Account("账号B").apply { token = "token" }
        val c = Account("账号C")

        val state = TaskUiStateFactory.from(listOf(a, b, c), false, 0, emptyList())

        assertEquals(2, state.runnableAccounts)
        assertEquals(1, state.dualPlatformAccounts)
        assertTrue(state.canRun)
    }

    @Test
    fun `运行中时禁用启动按钮并展示进度`() {
        val state = TaskUiStateFactory.from(emptyList(), true, 42, listOf("开始运行"))

        assertFalse(state.canRun)
        assertEquals("42", state.totalGainedText)
        assertEquals(1, state.logs.size)
    }

    @Test
    fun `日志存在错误时标记失败状态`() {
        val state = TaskUiStateFactory.from(emptyList(), false, 0, listOf("正常", "❌ 网络错误"))

        assertTrue(state.hasFailures)
    }

    @Test
    fun `只有设备登录时提示补充积分登录`() {
        val account = Account("账号A").apply { appToken = "app-token" }

        val state = TaskUiStateFactory.from(listOf(account), false, 0, emptyList())

        assertFalse(state.canRun)
        assertEquals("请完成积分登录", state.summary)
    }

    @Test
    fun `没有账户时提示先添加账户`() {
        val state = TaskUiStateFactory.from(emptyList(), false, 0, emptyList())

        assertFalse(state.canRun)
        assertEquals("请先添加账户", state.summary)
    }
}
