package com.silentime.ime

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Phase 1 脚手架 Activity。
 * 后续会改造为设置/引导页，用于开启 SilentIME 输入法。
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tv = TextView(this).apply {
            text = getString(R.string.scaffold_ready)
            textSize = 18f
            setPadding(64, 96, 64, 64)
        }
        setContentView(tv)
    }
}