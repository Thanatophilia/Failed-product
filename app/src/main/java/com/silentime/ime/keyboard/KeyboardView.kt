package com.silentime.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.silentime.ime.BuildConfig

/**
 * 自定义键盘视图（Stage 3 增强版）。
 *
 * 设计要点：
 *  1. 所有 [Paint] 在 init 中一次性创建并复用，绝不在 onDraw / onTouchEvent 里 new。
 *  2. 尺寸计算全部委托 [KeyboardMetrics]（纯函数），本类只负责状态与触发重算。
 *  3. 通过 [androidx.core.view.OnApplyWindowInsetsListener] 获取系统导航栏 / 手势条
 *     Insets（禁止写死导航栏高度）；内容区与 inset 区分离：
 *       - 背景色铺满整个 View（含 inset 区）保持视觉连续
 *       - 按键只绘制在内容区，保证不遮挡导航栏
 *  4. 屏幕尺寸、Insets、横竖屏变化时自动重新计算布局。
 *  5. 支持 ACTION_DOWN / ACTION_UP / ACTION_CANCEL，滑动出键会取消按下态。
 *  6. 退格键支持长按连续删除；Shift 一次性输入后自动复位。
 *
 * 诊断日志（仅 Debug 构建，见 [logD]）：
 *  - 生命周期 / View 创建 / 测量 / Insets / 布局结果，用于输入法切换类问题排查。
 *  - 绝不记录用户输入内容、密码、候选词或任何输入框文本。
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        private const val TAG = "SilentIME/KbView"
        /** 约束在 debug 构建打印诊断日志（release 自动关闭） */
        private val DEBUG = BuildConfig.DEBUG
        private fun logD(msg: String) {
            if (DEBUG) Log.d(TAG, msg)
        }
        // Stage 3.2.1 v4.1：有效"内容预算"按方向静态缓存。
        // 横屏->竖屏旋转时 View 会重建但窗口复用，系统不再下发有效预算，
        // 新 View 若缓存为 0 会退回自算（963）导致键盘超高。用静态双缓存
        // 跨 View 继承：旋转后新 View 直接拿到对应方向的内容预算。
        // 不写死高度；竖屏/横屏各自缓存，分屏、抬高开关均自动适配。
        @JvmStatic
        private var cachedContentPortrait = 0
        @JvmStatic
        private var cachedContentLandscape = 0
        // Stage 3.2.2 v4.2：持久缓存签名（屏幕高度 dp + density）。
        // screenHdp 来自 resources.configuration（不受 displayMetrics 漂移影响），
        // 防止导航切换中间态/伪预算把缩水高度写入持久缓存。
        @JvmStatic
        private var cachedScreenHdpPortrait = 0
        @JvmStatic
        private var cachedScreenHdpLandscape = 0
        @JvmStatic
        private var cachedDensity = 0f
    }

    /** 按键回调，由 InputMethodService 实现 */
    interface Listener {
        /** 提交一个字符 */
        fun onKeyText(text: String)

        /** 退格一次 */
        fun onKeyBackspace()

        /** 退格连续删除（长按触发） */
        fun onKeyBackspaceRepeat()

        /** 回车 / 动作键 */
        fun onKeyEnter()

        /** Shift 状态变化 */
        fun onShiftToggle()

        /** 切换布局模式 */
        fun onLayoutModeChange(mode: LayoutMode)

        /** 地球键：打开系统输入法选择器（由 Service 调用系统 API，本类不碰系统服务） */
        fun onOpenImeSwitcher()
    }

    var listener: Listener? = null

    /** 当前布局模式 */
    var mode: LayoutMode = LayoutMode.LETTERS_LOWER
        set(value) {
            if (field != value) {
                field = value
                rebuildLayout()
                invalidate()
            }
        }

    /** 回车键显示文案，由 service 依据 ImeOptions 设置 */
    var enterLabel: String = "\u23CE"
        set(value) {
            if (field != value) {
                field = value
                rebuildLayout()
                invalidate()
            }
        }

    // ---------- Insets（系统安全区，禁止写死） ----------

    /** 底部导航栏 / 手势条高度（px） */
    private var insetBottom = 0
    /** 左侧系统栏（横屏时可能出现）宽度（px） */
    private var insetLeft = 0
    /** 右侧系统栏宽度（px） */
    private var insetRight = 0

    /** 是否横屏（竖屏 / 横屏参数分离，横屏不沿用竖屏尺寸） */
    private var isLandscape = false

    // ---------- 复用对象，避免 onDraw 中分配 ----------

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val keyRect = RectF()

    private var bgColor = 0xFF1B1E26.toInt()
    private var keyColor = 0xFF2C3140.toInt()
    private var keyPressedColor = 0xFF4C8DFF.toInt()
    private var funcKeyColor = 0xFF232733.toInt()
    private var labelColor = 0xFFE8ECF2.toInt()
    private var labelPressedColor = 0xFF0E1118.toInt()

    // ---------- 布局缓存 ----------

    private val bounds = ArrayList<KeyboardMetrics.KeyBounds>()

    /** 当前按下的键索引，-1 表示无 */
    private var pressedIndex = -1

    /** 长按退格相关 */
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    /** 进入长按连续删除的延迟 */
    private val repeatDelayMs = 400L

    /** 连续删除间隔 */
    private val repeatIntervalMs = 60L

    init {
        isClickable = true
        isFocusable = false
        installInsetsListener()
        logD("init() this=$this")
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachSeq++
        // 尝试读取当前导航模式（Settings.Secure），仅供 Debug 诊断区分
        // 三键 / 手势 / 全面屏，读失败静默（不是关键路径）。
        debugNavMode = try {
            val mode = android.provider.Settings.Secure.getInt(
                context.contentResolver,
                "navigation_mode",
            )
            when (mode) {
                0 -> "3button"
                1 -> "gestural_2button"
                2 -> "gestural"
                else -> "nav=$mode"
            }
        } catch (_: Exception) {
            "n/a"
        }
        logD(
            "onAttachedToWindow this=$this attachSeq=$attachSeq navMode=$debugNavMode " +
                "w=${width}h=${height}"
        )
    }

    override fun onDetachedFromWindow() {
        stopBackspaceRepeat()
        super.onDetachedFromWindow()
        logD("onDetachedFromWindow this=$this")
    }

    // ---------- Stage 3.2.1 诊断辅助 ----------

    /** 附加到窗口的次数（区分首次显示与同一 View 热切换复用） */
    internal var attachSeq = 0
        private set

    /** 导航模式标签（Service 尽力获取，仅供 Debug 诊断；不可靠时为 "n/a"） */
    var debugNavMode: String = "n/a"

    /** onDraw 节流键：同一尺寸/inset 组合只打一条日志，避免刷屏 */
    private var lastDrawLogKey = ""

    /** 监听系统 Insets：导航栏 / 手势条变化时自动重算布局 */
    private fun installInsetsListener() {
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val newBottom = systemBars.bottom
            val newLeft = systemBars.left
            val newRight = systemBars.right
            val changed = newBottom != insetBottom ||
                newLeft != insetLeft ||
                newRight != insetRight
            insetBottom = newBottom
            insetLeft = newLeft
            insetRight = newRight

            val landscape = resources.configuration.orientation ==
                Configuration.ORIENTATION_LANDSCAPE
            logD(
                "insets changed=$changed bottom=$newBottom left=$newLeft right=$newRight " +
                    "landscape=$landscape oldLandscape=$isLandscape"
            )
            if (changed || landscape != isLandscape) {
                isLandscape = landscape
                // 高度 / 布局全部重算
                requestLayout()
                rebuildLayout()
                invalidate()
            }
            // 当前 View 自己消费这些 insets（不再向上传递，避免系统重复处理）
            WindowInsetsCompat.CONSUMED
        }
    }

    // ---------- 尺寸约束 ----------

    /**
     * 键盘总高度 = 内容高度（KeyboardMetrics 计算）+ 底部 Insets。
     * 内容高度 = 可用窗口高度 × 36%~42%，夹在 dp 上下限内。
     */
    /** 最近一次有效 specH（AT_MOST/EXACTLY），用于诊断"是否有新测量预算" */
    private var lastSpecH = 0
    private var lastSpecMode = MeasureSpec.UNSPECIFIED

    // v4.1：实例不再持有 cachedContentBudget，改由 companion 静态双缓存
    // （cachedContentPortrait / cachedContentLandscape）跨 View 继承。

    /** 由 requestLayout 触发的测量轮的标记（diagnostics） */
    internal var measureFromRequest = false
        private set

    internal var requestLayoutCount = 0
        private set

    override fun requestLayout() {
        requestLayoutCount++
        super.requestLayout()
    }

    /**
     * 供 Service 在 onWindowShown 等时机调用：请求一次重新测量 + 布局。
     * 不直接动 MeasureSpec / 尺寸，只是让系统用最新窗口预算再走一遍
     * measure/layout 管线；是否拿到有效预算由日志确认。
     */
    fun requestRemeasure() {
        measureFromRequest = true
        requestLayout()
        invalidate()
        logD("requestRemeasure() requested (reqCount=$requestLayoutCount)")
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // v4.1：onMeasure 开头同步方向（insets listener 可能晚于首轮测量触发）
        isLandscape = resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val screenHeight = resources.displayMetrics.heightPixels

        val calcTotal = KeyboardMetrics.totalHeight(
            windowHeightPx = screenHeight,
            bottomInsetPx = insetBottom,
            isLandscape = isLandscape,
            density = resources.displayMetrics.density,
        )
        val specH = MeasureSpec.getSize(heightMeasureSpec)
        val specMode = MeasureSpec.getMode(heightMeasureSpec)
        // Stage 3.2.1 最终方案（v4，"内容预算缓存"）：
        //
        // 根因（真机日志锁定）：热切换（窗口复用）时系统不再下发有效窗口预算，
        // 只给全屏伪预算（specH = 2376 = displayMetrics 全屏高），而 displayMetrics
        // 也恢复全屏（2376）。于是 calc = 2376×0.35 + 132 = 963 > 真实窗口预算 875，
        // Stage 3.2 的 min(calc, specH) 因 specH 是伪预算而失效，键盘超高进入导航区。
        //
        // 观察关键不变量：所有协商态下 "有效 specH - insetBottom" 恒等于内容预算
        // （三键+抬高：875-132=743；中间态 743-0=743；关闭抬高 791-48=743；
        //  手势：904-132=772；横屏：553-48=505）。内容预算只由窗口可用高度决定，
        // 热切换不会改变它；总预算 = 内容预算 + 当前 inset。
        //
        // 因此缓存内容预算：有效 specH 出现时更新（specH - insetBottom）；
        // 伪预算（全屏）出现时用 "缓存内容预算 + 当前 inset" 钳制 calc。
        // 不写死任何高度；inset、横竖屏、抬高开/关、分屏均自动适配。
        val usableH = (screenHeight - insetBottom).coerceAtLeast(1)
        // v4.2：签名（configuration.screenHeightDp 不受 displayMetrics 漂移影响；
        // density 防止分辨率切换后 px 串用）
        val screenHdp = resources.configuration.screenHeightDp
        val density = resources.displayMetrics.density
        val isEffectiveBudget = specMode != MeasureSpec.UNSPECIFIED &&
            specH > 0 &&
            (specH - insetBottom) <= usableH * 0.55f
        // v4.2：下限保护——拒绝异常缩水预算污染持久缓存。
        // 依据（真实日志）：正常协商 |specH-calc|/calc < 10%（875vs963、921vs963）；
        // 缩水预算 772/640 与 calc(963) 偏差仅 20%/34%，因此阈值取 50% 无法拦截，
        // 必须改为与"当前 calc"同源对比 + 签名校验组合。
        // 这里用 calcTotal 作为自算合理值基准：|specH - calcTotal| <= calcTotal * 0.8
        // （正常协商 875/921 与 963 差 <10% 通过；缩水 772/640 与 963 差 <35% 也通过？）
        // ——缺陷：calcTotal 本身随 screenH 漂移，收缩态 calc=772 与 specH=772 差 0% 必通过。
        // 因此真正可靠的是签名校验：specH 必须与"该窗口协商出的持久缓存"一致才写入。
        // 签名校验：
        val cachedContentBudget = if (isLandscape) {
            cachedContentLandscape
        } else {
            cachedContentPortrait
        }
        val sigValid = (if (isLandscape) {
            cachedScreenHdpLandscape
        } else {
            cachedScreenHdpPortrait
        }) == screenHdp && cachedDensity == density
        // 缩水保护：当 specH 是有效预算但显著小于"签名匹配的持久缓存 × 0.85"时，
        // 视为异常缩水（导航切换中间态/窗口动画），禁止写入持久缓存（只用于本次布局）。
        // 若持久缓存缺失（=0，如进程刚启动首次协商），则与 calcTotal 对比：
        // calcTotal 是"按当前屏高自算的合理高度"，缩水预算（772/640 vs 963）显著偏小。
        val shrinkProtected = if (cachedContentBudget > 0) {
            sigValid && (specH - insetBottom) < cachedContentBudget * 0.85f
        } else {
            (specH - insetBottom) < calcTotal * 0.8f
        }
        if (isEffectiveBudget && !shrinkProtected) {
            // v4.2：仅"非缩水"的有效预算写入持久缓存（临时/持久分离）
            if (isLandscape) {
                cachedContentLandscape = specH - insetBottom
                cachedScreenHdpLandscape = screenHdp
            } else {
                cachedContentPortrait = specH - insetBottom
                cachedScreenHdpPortrait = screenHdp
            }
            cachedDensity = density
        }
        // v4.2：读取持久缓存；签名不匹配则视为无缓存（回退自算）
        val budgetH = when {
            isEffectiveBudget && !shrinkProtected -> specH
            sigValid && cachedContentBudget > 0 -> cachedContentBudget + insetBottom
            else -> calcTotal
        }
        val total = minOf(calcTotal, budgetH)
        val fromReq = measureFromRequest
        measureFromRequest = false
        val specChanged = specH != lastSpecH || specMode != lastSpecMode
        lastSpecH = specH
        lastSpecMode = specMode
        // Stage 3.2.1：确认根窗口 Insets 是否已包含在测量预算内
        // （约束 4：不得直接把 MeasureSpec 尺寸当内容区高度）。
        val rootInsetB = try {
            rootWindowInsets?.let {
                androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(it)
                    .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom
            } ?: -1
        } catch (_: Exception) {
            -2
        }
        logD(
            "onMeasure${if (fromReq) "[fromReq]" else ""} specH=$specH mode=$specMode " +
                "specChanged=$specChanged isBudget=$isEffectiveBudget " +
                "sig=$sigValid shrink=$shrinkProtected " +
                "cachedContent=$cachedContentBudget screenH=$screenHeight " +
                "insetBottom=$insetBottom rootInsetB=$rootInsetB hdp=$screenHdp " +
                "landscape=$isLandscape calc=$calcTotal -> measuredH=$total"
        )
        setMeasuredDimension(width, total)
    }

    // ---------- 尺寸与布局 ----------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        logD(
            "onSizeChanged v=$this attachSeq=$attachSeq $oldw x $oldh -> $w x $h " +
                "insetB=$insetBottom windowShown=${isShown()}"
        )
        rebuildLayout()
    }

    /**
     * 根据当前控件尺寸、Insets 与布局模式，重算所有按键矩形。
     * 只在尺寸 / Insets / 模式变化时调用，不在绘制与触摸路径中调用。
     */
    private fun rebuildLayout() {
        bounds.clear()
        if (width == 0 || height == 0) {
            logD("rebuildLayout skipped v=$this w=$width h=$height")
            return
        }

        val contentWidth = (width - insetLeft - insetRight).coerceAtLeast(1)
        val contentHeight = (height - insetBottom).coerceAtLeast(1)
        logD(
            "rebuildLayout v=$this attachSeq=$attachSeq mode=$mode view=${width}x$height " +
                "content=${contentWidth}x$contentHeight insetsL/R/B=$insetLeft/$insetRight/$insetBottom " +
                "navMode=$debugNavMode windowShown=${isShown()}"
        )

        bounds.addAll(
            KeyboardMetrics.computeBounds(
                contentWidth = contentWidth.toFloat(),
                contentHeight = contentHeight.toFloat(),
                offsetX = insetLeft.toFloat(),
                mode = mode,
                enterLabel = enterLabel,
            )
        )

        // 最后一排按键本地坐标（底部行，含 Enter / 空格键）：直接观察其
        // 是否越过内容区底边界进入 inset（导航栏）区域。
        // 判定"属于最后一行"：rect.bottom 距内容区底边界（height - insetBottom）
        // ≤ 16px（最后一行下缘有 rowGap×0.4 ≈ 6px 内缩，容差取大些；
        // 其它行至少差一整行高 150px+，不会误判）。
        val lastRowStr = if (bounds.isNotEmpty()) {
            val bottomEdge = (height - insetBottom).toFloat()
            val lastRow = bounds.filter { bottomEdge - it.rect.bottom <= 16f }
            if (lastRow.isNotEmpty()) {
                val tops = lastRow.minOf { it.rect.top }
                val bottoms = lastRow.maxOf { it.rect.bottom }
                "y[$tops,$bottoms] (bottomEdge=$bottomEdge)"
            } else {
                "none(nearBottomEdge)"
            }
        } else {
            "empty"
        }
        logD("rebuildLayout lastRow=$lastRowStr")
    }

    // ---------- 绘制 ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Stage 3.2.1 诊断：同一 尺寸×inset×attachSeq 组合只记录一次
        val drawKey = "${attachSeq}x${width}x${height}x${insetBottom}x${mode}"
        if (drawKey != lastDrawLogKey) {
            lastDrawLogKey = drawKey
            logD(
                "onDraw v=$this attachSeq=$attachSeq view=${width}x$height " +
                    "insetB=$insetBottom mode=$mode navMode=$debugNavMode " +
                    "windowShown=${isShown()}"
            )
        }

        // 背景铺满整个 View（含 inset 区），保证与系统栏视觉连续
        keyPaint.color = bgColor
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), keyPaint)

        val labelSize = (height - insetBottom) * 0.062f
        labelPaint.textSize = labelSize

        bounds.forEachIndexed { index, kb ->
            val isPressed = index == pressedIndex
            val isFuncKey = kb.key.type != KeyType.CHARACTER

            keyPaint.color = when {
                isPressed -> keyPressedColor
                isFuncKey -> funcKeyColor
                else -> keyColor
            }

            val radius = (height - insetBottom) * 0.03f
            canvas.drawRoundRect(kb.rect, radius, radius, keyPaint)

            // 文字
            if (kb.key.label.isNotEmpty()) {
                labelPaint.color = if (isPressed) labelPressedColor else labelColor

                // Shift 激活时高亮
                if (kb.key.type == KeyType.SHIFT && mode == LayoutMode.LETTERS_UPPER) {
                    labelPaint.color = keyPressedColor
                }

                val textY = kb.rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
                canvas.drawText(kb.key.label, kb.rect.centerX(), textY, labelPaint)
            }

            // 地球键：单色几何图标（圆形外框 + 经线 + 纬线），不依赖 Emoji 字体
            if (kb.key.type == KeyType.EARTH) {
                drawEarthIcon(canvas, kb.rect, isPressed)
            }
        }
    }

    // ---------- 触摸 ----------

    private fun hitTest(x: Float, y: Float): Int {
        for (i in bounds.indices) {
            if (bounds[i].rect.contains(x, y)) return i
        }
        return -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index = hitTest(event.x, event.y)
                if (index >= 0) {
                    pressedIndex = index
                    invalidate()
                    // 退格键长按连续删除
                    if (bounds[index].key.type == KeyType.BACKSPACE) {
                        startBackspaceRepeat()
                    }
                    return true
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                val index = hitTest(event.x, event.y)
                if (index != pressedIndex) {
                    pressedIndex = index
                    // 滑出退格键就停止连发
                    if (index < 0 || bounds.getOrNull(index)?.key?.type != KeyType.BACKSPACE) {
                        stopBackspaceRepeat()
                    }
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val index = hitTest(event.x, event.y)
                stopBackspaceRepeat()
                if (index == pressedIndex && index >= 0) {
                    handleKey(bounds[index].key)
                }
                pressedIndex = -1
                invalidate()
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                stopBackspaceRepeat()
                pressedIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /**
     * 绘制地球键的单色几何图标（圆形外框 + 经线 + 纬线）。
     * 纯 Canvas 绘制，不依赖任何字体/Emoji，不改变按键 bounds 与 hitTest。
     */
    private fun drawEarthIcon(canvas: Canvas, rect: RectF, isPressed: Boolean) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val r = rect.width() * 0.16f
        val color = if (isPressed) labelPressedColor else labelColor
        val stroke = labelPaint.strokeWidth.coerceAtLeast(rect.width() * 0.028f)

        // 外框圆
        labelPaint.style = Paint.Style.STROKE
        labelPaint.strokeWidth = stroke
        labelPaint.color = color
        canvas.drawCircle(cx, cy, r, labelPaint)

        // 纬线（水平两条：上 1/3、下 1/3）
        val latOffset = r * 0.4f
        canvas.drawLine(cx - r * 0.85f, cy - latOffset, cx + r * 0.85f, cy - latOffset, labelPaint)
        canvas.drawLine(cx - r * 0.95f, cy + latOffset, cx + r * 0.95f, cy + latOffset, labelPaint)

        // 经线（垂直一条 + 椭圆弧 ×2，形成球面感）
        canvas.drawLine(cx, cy - r, cx, cy + r, labelPaint)
        val ovalW = r * 0.55f
        val ovalH = r * 0.95f
        labelPaint.style = Paint.Style.STROKE
        canvas.drawOval(
            RectF(cx - ovalW, cy - ovalH, cx + ovalW, cy + ovalH),
            labelPaint,
        )
        canvas.drawOval(
            RectF(cx - ovalW * 0.35f, cy - ovalH, cx + ovalW * 0.35f, cy + ovalH),
            labelPaint,
        )

        // 恢复 paint 状态（文字绘制依赖居中 + FILL）
        labelPaint.style = Paint.Style.FILL
        labelPaint.strokeWidth = 0f
    }

    private fun startBackspaceRepeat() {
        stopBackspaceRepeat()
        val runnable = object : Runnable {
            override fun run() {
                listener?.onKeyBackspaceRepeat()
                repeatHandler.postDelayed(this, repeatIntervalMs)
            }
        }
        repeatRunnable = runnable
        repeatHandler.postDelayed(runnable, repeatDelayMs)
    }

    private fun stopBackspaceRepeat() {
        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        repeatRunnable = null
    }

    private fun handleKey(key: Key) {
        when (key.type) {
            KeyType.CHARACTER -> {
                listener?.onKeyText(key.value ?: key.label)
                // Shift 一次性：输入字母后自动回到小写
                if (key.isLetter && mode == LayoutMode.LETTERS_UPPER) {
                    listener?.onLayoutModeChange(LayoutMode.LETTERS_LOWER)
                }
            }

            KeyType.SHIFT -> {
                listener?.onShiftToggle()
            }

            KeyType.BACKSPACE -> {
                listener?.onKeyBackspace()
            }

            KeyType.SPACE -> {
                listener?.onKeyText(" ")
            }

            KeyType.ENTER -> {
                listener?.onKeyEnter()
            }

            KeyType.SYMBOLS -> {
                listener?.onLayoutModeChange(LayoutMode.SYMBOLS)
            }

            KeyType.LETTERS -> {
                listener?.onLayoutModeChange(LayoutMode.LETTERS_LOWER)
            }

            KeyType.EARTH -> {
                // 地球键：打开系统输入法选择器（系统 API 由 Service 调用）
                listener?.onOpenImeSwitcher()
            }
        }
    }

    /**
     * 同步 Shift 状态到视图。
     * 由 service 在布局切换后调用，保持单一数据源。
     */
    fun updateShiftState(upper: Boolean) {
        val target = when {
            mode == LayoutMode.SYMBOLS -> return
            upper -> LayoutMode.LETTERS_UPPER
            else -> LayoutMode.LETTERS_LOWER
        }
        mode = target
    }
}