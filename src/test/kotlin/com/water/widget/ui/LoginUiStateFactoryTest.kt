package com.water.widget.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginUiStateFactoryTest {
    @Test
    fun `积分登录说明可获得更多积分`() {
        val state = LoginUiStateFactory.from(LoginPlatform.ALIPAY, false, false, "13800000000", "", "")

        assertEquals("积分登录", state.platformTitle)
        assertEquals("补充：完成支付宝端积分任务，获得更多积分", state.platformDescription)
        assertEquals("完成积分登录", state.actionLabel)
        assertTrue(state.canLoadCaptcha)
        assertFalse(state.canSendSms)
        assertFalse(state.canLogin)
    }

    @Test
    fun `设备登录标明为必需项`() {
        val state = LoginUiStateFactory.from(LoginPlatform.APP, true, true, "13800000000", "1234", "1234")

        assertEquals("设备登录", state.platformTitle)
        assertEquals("必需：同步设备、启动出水、钱包充值与 App 端积分任务", state.platformDescription)
        assertEquals("完成设备登录", state.actionLabel)
        assertTrue(state.canSendSms)
        assertTrue(state.canLogin)
    }

    @Test
    fun `手机号无效时不能刷新图形验证码`() {
        val state = LoginUiStateFactory.from(
            platform = LoginPlatform.ALIPAY,
            captchaLoaded = false,
            smsSent = false,
            phone = "123",
            graphCode = "",
            smsCode = ""
        )

        assertFalse(state.canLoadCaptcha)
        assertFalse(state.canSendSms)
        assertFalse(state.canLogin)
    }

    @Test
    fun `验证码为空时不能发送短信或登录`() {
        val noGraph = LoginUiStateFactory.from(LoginPlatform.ALIPAY, true, false, "13800000000", "", "")
        val noSms = LoginUiStateFactory.from(LoginPlatform.ALIPAY, true, true, "13800000000", "1234", "")
        val ready = LoginUiStateFactory.from(LoginPlatform.ALIPAY, true, true, "13800000000", "1234", "5678")

        assertFalse(noGraph.canSendSms)
        assertFalse(noSms.canLogin)
        assertTrue(ready.canLogin)
    }
}
