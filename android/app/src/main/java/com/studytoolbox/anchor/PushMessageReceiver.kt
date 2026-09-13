package com.studytoolbox.anchor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import cn.jpush.android.api.JPushInterface
import cn.jpush.android.api.JPushMessage
import cn.jpush.android.api.NotificationMessage
import cn.jpush.android.service.JPushMessageReceiver

class PushMessageReceiver : JPushMessageReceiver() {

    override fun onRegister(context: Context, registrationId: String) {
        Log.i(TAG, "onRegister rid=$registrationId")
        if (StudyToolboxApp.hasChildChatKey(context)) {
            JPushInterface.setAlias(context, StudyToolboxApp.ALIAS_SEQUENCE, StudyToolboxApp.ALIAS)
        }
    }

    override fun onConnected(context: Context, isConnected: Boolean) {
        Log.i(TAG, "onConnected=$isConnected")
    }

    override fun onAliasOperatorResult(context: Context, message: JPushMessage) {
        val code = message.errorCode
        Log.i(TAG, "onAlias seq=${message.sequence} code=$code alias=${message.alias}")
        if (code == 6002) {
            // 超时：稍后再试一次。Receiver 不持有 Handler，用线程 sleep。
            Thread {
                try {
                    Thread.sleep(3_000)
                    JPushInterface.setAlias(
                        context.applicationContext,
                        StudyToolboxApp.ALIAS_SEQUENCE,
                        StudyToolboxApp.ALIAS
                    )
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }.start()
        }
    }

    override fun onNotifyMessageOpened(context: Context, message: NotificationMessage) {
        super.onNotifyMessageOpened(context, message)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(BuildConfig.TOOLBOX_URL + "#chatPanel")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(intent)
    }

    companion object {
        private const val TAG = "ToolboxPush"
    }
}
