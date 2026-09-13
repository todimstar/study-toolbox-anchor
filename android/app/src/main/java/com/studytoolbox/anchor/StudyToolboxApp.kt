package com.studytoolbox.anchor

import android.app.Application
import android.content.Context
import android.util.Log
import cn.jpush.android.api.JPushInterface

class StudyToolboxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 合规：未配对不 init。配对成功后由 MainActivity.enterApp 调 initAndBindAlias。
        if (hasChildChatKey(this)) {
            initAndBindAlias(this)
        }
    }

    companion object {
        private const val TAG = "ToolboxPush"
        private const val PREFS_NAME = "study_toolbox_native_prefs"
        private const val KEY_CHILD_CHAT_KEY = "child_chat_key"
        const val ALIAS = "queen"
        const val ALIAS_SEQUENCE = 1001

        fun hasChildChatKey(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CHILD_CHAT_KEY, "")
                .orEmpty()
                .isNotBlank()

        fun initAndBindAlias(context: Context) {
            val app = context.applicationContext
            JPushInterface.setDebugMode(false)
            JPushInterface.init(app)
            JPushInterface.setAlias(app, ALIAS_SEQUENCE, ALIAS)
            Log.i(TAG, "JPush init + setAlias($ALIAS)")
        }
    }
}
