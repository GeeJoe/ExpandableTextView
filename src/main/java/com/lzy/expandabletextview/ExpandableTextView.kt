package com.lzy.expandabletextview

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.*
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.util.AttributeSet
import android.view.View
import androidx.annotation.NonNull
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

class ExpandableTextView : AppCompatTextView {

  constructor(context: Context?) : super(context) {
    init(null, 0)
  }

  constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
    init(attrs, 0)
  }

  constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
    init(attrs, defStyleAttr)
  }

  private lateinit var mPaint: TextPaint
  private var mCurrentLineCount = 0
  private var mLineCount = 0
  private var mWidth = 0
  private var onExpandCollapseListener: OnExpandCollapseListener? = null

  private var mContent: CharSequence? = null
  private var mCollapsedMaxLines = DEFAULT_COLLAPSED_MAX_LINE
  private var mExpandedMaxLines = DEFAULT_EXPANDED_MAX_LINE
  private var mNeedAnimation = true
  private var mExpandedTextColor = 0
  private var mCollapsedTextColor = 0
  private lateinit var mExpandedString: String
  private lateinit var mCollapsedString: String
  private lateinit var mCutContentSuffix: String
  private var mSuffixDrawableSize: Int = 0
  private var mCollapsedDrawable: Drawable? = null
  private var mExpandedDrawable: Drawable? = null

  private var mHasInitWidth = false

  private var mDynamicLayout: DynamicLayout? = null

  private fun init(attrs: AttributeSet?, defStyleAttr: Int) {
    movementMethod = LinkMovementMethod.getInstance()

    val a = context.obtainStyledAttributes(
        attrs, R.styleable.ExpandableTextView, defStyleAttr, 0)
    mContent = a.getString(R.styleable.ExpandableTextView_ep_content)
    mCollapsedMaxLines = a.getInt(R.styleable.ExpandableTextView_collapsedMaxLine, DEFAULT_COLLAPSED_MAX_LINE)
    mExpandedMaxLines = a.getInt(R.styleable.ExpandableTextView_expandedMaxLine, DEFAULT_EXPANDED_MAX_LINE)
    mNeedAnimation = a.getBoolean(R.styleable.ExpandableTextView_needAnimation, true)
    mCollapsedString = a.getString(R.styleable.ExpandableTextView_collapsedText) ?: TEXT_COLLAPSED
    mCutContentSuffix = a.getString(R.styleable.ExpandableTextView_cutContentSuffix)
        ?: TEXT_CUT_CONTENT_SUFFIX
    mExpandedString = a.getString(R.styleable.ExpandableTextView_expandedText) ?: TEXT_EXPEND
    mExpandedTextColor = a.getColor(R.styleable.ExpandableTextView_expandedTextColor, Color.BLACK)
    mCollapsedTextColor = a.getColor(R.styleable.ExpandableTextView_collapsedTextColor, Color.BLACK)
    mSuffixDrawableSize = a.getDimensionPixelSize(R.styleable.ExpandableTextView_suffixDrawableSize, 0)
    val expandedDrawableResId = a.getResourceId(R.styleable.ExpandableTextView_expandedDrawable, 0)
    val collapsedDrawableResId = a.getResourceId(R.styleable.ExpandableTextView_collapsedDrawable, 0)
    if (expandedDrawableResId != 0) {
      mExpandedDrawable = ContextCompat.getDrawable(context, expandedDrawableResId)?.apply {
        setBounds(0, 0, mSuffixDrawableSize, mSuffixDrawableSize)
      }
    }
    if (collapsedDrawableResId != 0) {
      mCollapsedDrawable = ContextCompat.getDrawable(context, collapsedDrawableResId)?.apply {
        setBounds(0, 0, mSuffixDrawableSize, mSuffixDrawableSize)
      }
    }
    a.recycle()

    // Collapsed by default
    mCurrentLineCount = mCollapsedMaxLines

    mPaint = paint.apply {
      style = Paint.Style.FILL_AND_STROKE
    }
  }

  fun setContent(content: String) {
    val dynamicLayout = DynamicLayout(content, mPaint, mWidth, Layout.Alignment.ALIGN_NORMAL,
        0.0f, 0.0f, true)
    mLineCount = dynamicLayout.lineCount
    mContent = content
    mDynamicLayout = dynamicLayout

    setContentInternal(content)
  }

  private fun setContentInternal(content: CharSequence) {
    val dynamicLayout = mDynamicLayout ?: return
    text = when (getContentState()) {
      ContentState.NONE -> {
        content
      }
      ContentState.COLLAPSED,
      ContentState.CUT_EXPANDED -> {
        getCutContent(content, dynamicLayout)
      }
      ContentState.FULL_EXPANDED -> {
        getFullExpandedContent(content, dynamicLayout)
      }
    }
  }

  private fun getFullExpandedContent(content: CharSequence, dynamicLayout: DynamicLayout): SpannableStringBuilder {
    val ssb = SpannableStringBuilder()

    ssb.append(content)

    if (dynamicLayout.getLineWidth(dynamicLayout.lineCount - 1) + getSuffixWidth() >=
        MAX_LINE_WIDTH_PERCENT * mWidth) {
      ssb.appendln().append(getSuffixString()?.trim())
      mCurrentLineCount += 1
    } else {
      ssb.append(getSuffixString())
    }

    ssb.setSpan(object : ClickableSpan() {
      override fun onClick(widget: View) {
        action()
        onExpandCollapseListener?.onClick(ExpandableState.COLLAPSED)
      }

      override fun updateDrawState(ds: TextPaint) {
        super.updateDrawState(ds)
        ds.color = mExpandedTextColor
        ds.isUnderlineText = false
        ds.isFakeBoldText = true
      }
    }, ssb.length - mExpandedString.length, ssb.length, Spannable.SPAN_INCLUSIVE_EXCLUSIVE)

    appendSuffixDrawable(ssb)

    return ssb
  }

  private fun getCutContent(content: CharSequence, dynamicLayout: DynamicLayout)
      : SpannableStringBuilder {
    val ssb = SpannableStringBuilder()

    val index = mCurrentLineCount - 1
    val endPosition = dynamicLayout.getLineEnd(index)
    val startPosition = dynamicLayout.getLineStart(index)
    val lineWidth = dynamicLayout.getLineWidth(index)
    val clickableString = getClickableString()
    val suffixString = getSuffixString()

    val fitPosition = getCutPosition(endPosition, startPosition, lineWidth, 0f)
    var substring = content.toString().substring(0, fitPosition)
    if (substring.endsWith(NEW_LINE)) {
      substring = substring.substring(0, substring.length - NEW_LINE.length)
    }
    ssb.append(substring)

    if (suffixString == null) {
      return ssb
    }

    val clickableStringIndex = substring.length + (suffixString.length - clickableString.length)
    ssb.append(suffixString)
    ssb.setSpan(object : ClickableSpan() {
      override fun onClick(widget: View) {
        action()
        onExpandCollapseListener?.onClick(if (isCollapsed()) ExpandableState.EXPANDED else ExpandableState.COLLAPSED)
      }

      override fun updateDrawState(ds: TextPaint) {
        super.updateDrawState(ds)
        ds.color = if (isCollapsed()) mCollapsedTextColor else mExpandedTextColor
        ds.isUnderlineText = false
        ds.isFakeBoldText = true
        ds.bgColor = Color.TRANSPARENT
      }
    }, clickableStringIndex, clickableStringIndex + clickableString.length, Spannable.SPAN_INCLUSIVE_EXCLUSIVE)

    appendSuffixDrawable(ssb)

    return ssb
  }

  private fun appendSuffixDrawable(ssb: SpannableStringBuilder) {
    // Add placeholder for drawable
    ssb.append(SPACE).append(SPACE)
    getSuffixDrawable()?.let {
      ssb.setSpan(CenterImageSpan(it, ImageSpan.ALIGN_BASELINE),
          ssb.length - 1, ssb.length, Spannable.SPAN_INCLUSIVE_INCLUSIVE)
    }
  }

  private fun action() {
    if (mNeedAnimation) {
      actionWithAnimation(isCollapsed())
    } else {
      actionWithoutAnimation(isCollapsed())
    }
  }

  private fun actionWithAnimation(isCollapsed: Boolean) {
    val currentHeight = height
    val lineHeight = (currentHeight - paddingTop - paddingBottom) / mCurrentLineCount.toFloat()

    updateCurrentLineCount(isCollapsed)

    val targetLineCount = if (isCollapsed) {
      mCurrentLineCount.coerceAtMost(mExpandedMaxLines)
    } else {
      mCollapsedMaxLines
    }
    val targetHeight = (targetLineCount * lineHeight + paddingTop + paddingBottom).toInt()

    val valueAnimator = ValueAnimator.ofInt(currentHeight, targetHeight)
    valueAnimator.addUpdateListener { animation ->
      val value = animation.animatedValue as Int
      layoutParams.height = value
      requestLayout()
    }
    valueAnimator.interpolator = FastOutSlowInInterpolator()
    valueAnimator.duration = ANIMATION_DURATION
    valueAnimator.start()
  }

  private fun actionWithoutAnimation(isCollapsed: Boolean) {
    updateCurrentLineCount(isCollapsed)
  }

  private fun updateCurrentLineCount(isCollapsed: Boolean) {
    mCurrentLineCount = if (isCollapsed) {
      mLineCount.coerceAtMost(mExpandedMaxLines)
    } else {
      mCollapsedMaxLines
    }
    mContent?.let {
      setContentInternal(it)
    }
  }

  private fun getCutPosition(endPosition: Int, startPosition: Int,
                             lineWidth: Float, offset: Float): Int {
    val targetRemainWidth = lineWidth - getSuffixWidth()

    if (targetRemainWidth <= 0) {
      return endPosition
    }

    val textRemainLength = ((targetRemainWidth - offset) * (endPosition - startPosition)
        / lineWidth).toInt()

    if (textRemainLength <= 0) {
      return endPosition
    }

    val textRemainWidth = mPaint.measureText(
        mContent.toString().substring(startPosition, startPosition + textRemainLength))

    // return the endPosition if textRemainWidth is close to targetRemainWidth or
    // cut the content by the length of a space and calculate again
    return if (textRemainWidth <= targetRemainWidth) {
      startPosition + textRemainLength
    } else {
      getCutPosition(endPosition, startPosition, lineWidth, offset + mPaint.measureText(SPACE))
    }
  }

  private fun getSuffixDrawableWidth(): Int {
    val drawable = getSuffixDrawable() ?: return 0
    return drawable.bounds.right - drawable.bounds.left
  }

  private fun getSuffixDrawable(): Drawable? {
    return if (isCollapsed()) mCollapsedDrawable else mExpandedDrawable
  }

  private fun getSuffixWidth(): Int {
    return getSuffixString()?.let {
      (mPaint.measureText(it) + getSuffixDrawableWidth()).toInt()
    } ?: 0
  }

  private fun getClickableString(): String {
    return if (isCollapsed()) mCollapsedString else mExpandedString
  }

  private fun getSuffixString(): String? {
    return when (getContentState()) {
      ContentState.NONE -> {
        null
      }
      ContentState.COLLAPSED -> {
        "$mCutContentSuffix$SPACE$SPACE$mCollapsedString"
      }
      ContentState.CUT_EXPANDED -> {
        "$mCutContentSuffix$SPACE$SPACE$mExpandedString"
      }
      ContentState.FULL_EXPANDED -> {
        "$SPACE$SPACE$mExpandedString"
      }
    }
  }

  private fun isCollapsed(): Boolean {
    return mCurrentLineCount < mLineCount.coerceAtMost(mExpandedMaxLines)
  }

  private fun getContentState(): ContentState {
    return when {
      mLineCount <= mCollapsedMaxLines -> {
        ContentState.NONE
      }
      mLineCount <= mExpandedMaxLines -> {
        if (isCollapsed()) ContentState.COLLAPSED else ContentState.FULL_EXPANDED
      }
      else -> {
        if (isCollapsed()) ContentState.COLLAPSED else ContentState.CUT_EXPANDED
      }
    }
  }

  override fun onDraw(canvas: Canvas?) {
    super.onDraw(canvas)
    if (mHasInitWidth) {
      return
    }
    mWidth = width - paddingStart - paddingEnd
    if (mWidth != 0) {
      mContent?.let {
        mHasInitWidth = true
        setContent(it.toString())
      }
    }
  }

  internal class CenterImageSpan(private val drawable: Drawable, verticalAlignment: Int)
    : ImageSpan(drawable, verticalAlignment) {

    override fun getDrawable(): Drawable {
      return drawable
    }

    override fun draw(@NonNull canvas: Canvas, text: CharSequence,
                      start: Int, end: Int,
                      x: Float, top: Int, y: Int, bottom: Int, @NonNull paint: Paint) {
      val b = getDrawable()
      val fm = paint.fontMetricsInt
      val transY = ((y + fm.descent + y + fm.ascent) / 2 - b.bounds.bottom / 2)
      canvas.save()
      canvas.translate(x, transY.toFloat())
      b.draw(canvas)
      canvas.restore()
    }
  }

  enum class ExpandableState {
    COLLAPSED, EXPANDED
  }

  enum class ContentState {
    NONE, COLLAPSED, FULL_EXPANDED, CUT_EXPANDED
  }

  interface OnExpandCollapseListener {
    fun onClick(type: ExpandableState)
  }

  fun setOnExpandCollapseListener(onExpandCollapseListener: OnExpandCollapseListener) {
    this.onExpandCollapseListener = onExpandCollapseListener
  }

  companion object {
    private const val DEFAULT_COLLAPSED_MAX_LINE = 2
    private const val DEFAULT_EXPANDED_MAX_LINE = 6
    private const val SPACE = " "
    private const val NEW_LINE = "\n"
    private const val TEXT_COLLAPSED = "show more"
    private const val TEXT_EXPEND = "show less"
    private const val TEXT_CUT_CONTENT_SUFFIX = "..."

    private const val ANIMATION_DURATION = 300L
    private const val MAX_LINE_WIDTH_PERCENT = 0.95
    private const val TAG = "ExpandableTextView"
  }
}