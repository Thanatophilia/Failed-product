package com.silentime.ime.feedback

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.silentime.ime.BuildConfig
import com.silentime.ime.R
import com.silentime.ime.settings.KeyboardSettings
import com.silentime.ime.settings.SettingsRepository

/**
 * 按键反馈执行接口（Stage 4.4.2-R1）。
 *
 * 由 [com.silentime.ime.SilentImeService] 注入 [com.silentime.ime.keyboard.KeyboardView]；
 * 键盘视图不直接持有任何系统服务。反馈只在 SilentIME 自己的键盘按下时触发，
 * 不影响其他应用与系统界面。
 */
interface KeyFeedbackPlayer {
    /** 播放按键音（SoundPool + 自制原创 OGG） */
    fun playKeySound()

    /** 触发按键震动（Vibrator / VibratorManager 直接调用） */
    fun playKeyVibration()
    /** 长按退格连删的重复短震动（Stage 4.4.3-R1）：每次连删同步触发，短于单次按键 */
    fun playKeyVibrationRepeat()

    /** 释放资源（Service onDestroy 时调用，之后对象不可再用） */
    fun release()
}

/**
 * 默认实现（Stage 4.4.2-R1 本地反馈）：
 *  - 音效：SoundPool 预加载应用自带的原创 OGG（res/raw/key_click.ogg），
 *    不依赖 AudioManager.playSoundEffect / 系统触摸音效总开关
 *    （Settings.System.SOUND_EFFECTS_ENABLED）。
 *  - 震动：Vibrator / VibratorManager 直接振动（VibrationEffect.createOneShot），
 *    不依赖 View.performHapticFeedback / 系统触觉反馈总开关
 *    （Settings.System.HAPTIC_FEEDBACK_ENABLED）。
 *  - 权限：仅 VIBRATE（normal 权限，Manifest 声明即可，无需运行时申请）。
 *
 * 与系统全局设置的关系（任务书 63821-R1）：
 *  - 不读取、不修改 sound_effects_enabled / haptic_feedback_enabled；
 *  - 不绕过系统策略（响铃模式 / 勿扰对音频的限制由系统强制执行，正常尊重）；
 *  - 震动振幅使用 [VibrationEffect.DEFAULT_AMPLITUDE]——跟随用户系统"触觉强度"
 *    设置，不将任何数值视为跨设备统一的 MEDIUM 常量；
 *    最终以 realme GT Neo6 真机感知与底层马达输出验证为准。
 */
class FeedbackPlayer(
    private val context: Context,
) : KeyFeedbackPlayer {

    companion object {
        private const val TAG = "SilentIME/Feedback"
        private val DEBUG = BuildConfig.DEBUG
        private fun logD(msg: String) {
            if (DEBUG) Log.d(TAG, msg)
        }

        /** 按键震动时长（ms）：短促点击感 */
        private const val VIBE_DURATION_MS = 20L
        /** 长按退格连删重复震动时长（ms）：8ms 短促，60ms 连删间隔下形成“哒哒哒”颗粒感 */
        private const val REPEAT_VIBE_DURATION_MS = 8L
    }

    // ---------- 音效（SoundPool + 自制 OGG） ----------

    private var soundPool: SoundPool? = null
    private var clickSoundId = 0
    private var soundLoaded = false


    // ---------- 震动（Vibrator / VibratorManager） ----------

    /**
     * API 31+ 用 VibratorManager.defaultVibrator（系统推荐入口）；
     * API 26-30 用 Context.getSystemService(Vibrator)（VibratorManager 不存在）。
     * VibrationEffect.createOneShot 本身 API 26+，minSdk 26 直接可用。
     */
    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    init {
        // SoundPool：maxStreams=2，快速连拍时自动替换旧流，避免流堆积；
        // USAGE_ASSISTANCE_SONIFICATION 语义为"按键声/触感声"，尊重响铃模式与音量。
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(attrs)
            .build()
        clickSoundId = soundPool?.load(context, R.raw.key_click, 1) ?: 0
        soundPool?.setOnLoadCompleteListener { _, _, _ ->
            soundLoaded = true
            logD("sound loaded id=$clickSoundId")
        }
        // 仅诊断：马达能力（是否能控制振幅）
        val ampControl = vibrator?.hasAmplitudeControl()
        logD(
            "init hasVibrator=${vibrator?.hasVibrator()} " +
                "amplitudeControl=$ampControl soundId=$clickSoundId"
        )
    }

    override fun playKeySound() {
        val sp = soundPool ?: return
        if (!soundLoaded || clickSoundId == 0) return // 未加载完成静默跳过（不堆积）
        // Stage 4.4.2-R2：每次播放实时读取设置（SharedPreferences 内存缓存即时更新），
        // 拖动拉条后下一次按键立即生效；重启后同样从持久化值恢复。
        val v = SettingsRepository.soundVolume().coerceIn(0, 100) / 100f
        if (v <= 0f) return // 音量 0：跳过播放（不影响按键输入）
        sp.play(clickSoundId, v, v, 1, 0, 1.0f)
    }

    override fun playKeyVibration() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            val effect = VibrationEffect.createOneShot(
                VIBE_DURATION_MS,
                VibrationEffect.DEFAULT_AMPLITUDE,
            )
            v.vibrate(effect)
            logD("vibrate dur=${VIBE_DURATION_MS}ms amp=DEFAULT_AMPLITUDE")
        } catch (e: Exception) {
            // 极端情况（触觉服务不可用）静默，不影响按键
            logD("vibrate failed: ${e.message}")
        }
    }


    override fun playKeyVibrationRepeat() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            v.vibrate(
                VibrationEffect.createOneShot(
                    REPEAT_VIBE_DURATION_MS,
                    VibrationEffect.DEFAULT_AMPLITUDE,
                )
            )
            logD("repeatVibrate dur=${REPEAT_VIBE_DURATION_MS}ms amp=DEFAULT_AMPLITUDE")
        } catch (e: Exception) {
            // 极端情况（触觉服务不可用）静默，不影响按键
            logD("repeatVibrate failed: ${e.message}")
        }
    }

    override fun release() {
        soundPool?.release()
        soundPool = null
        clickSoundId = 0
        soundLoaded = false
        logD("released")
    }
}