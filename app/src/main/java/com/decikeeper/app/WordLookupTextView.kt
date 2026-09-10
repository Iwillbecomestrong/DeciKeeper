package com.decikeeper.app

import android.content.Context
import android.util.AttributeSet

/**
 * Compatibility class for the existing alarm layout XML.
 * Keep the real implementation in ui.WordLookupTextView.
 */
class WordLookupTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : com.decikeeper.app.ui.WordLookupTextView(context, attrs, defStyleAttr)
