package com.github.exact7.xtra.ui.view.chat

import android.annotation.SuppressLint
import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.MultiAutoCompleteTextView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.exact7.xtra.R
import com.github.exact7.xtra.model.chat.BttvEmote
import com.github.exact7.xtra.model.chat.ChatMessage
import com.github.exact7.xtra.model.chat.Chatter
import com.github.exact7.xtra.model.chat.Emote
import com.github.exact7.xtra.model.chat.FfzEmote
import com.github.exact7.xtra.model.chat.RecentEmote
import com.github.exact7.xtra.ui.chat.ChatFragment
import com.github.exact7.xtra.ui.common.ChatAdapter
import com.github.exact7.xtra.util.convertDpToPixels
import com.github.exact7.xtra.util.gone
import com.github.exact7.xtra.util.hideKeyboard
import com.github.exact7.xtra.util.loadImage
import com.github.exact7.xtra.util.showKeyboard
import com.github.exact7.xtra.util.toggleVisibility
import com.github.exact7.xtra.util.visible
import kotlinx.android.extensions.LayoutContainer
import kotlinx.android.synthetic.main.auto_complete_emotes_list_item.view.*
import kotlinx.android.synthetic.main.view_chat.view.*
import kotlin.math.max
import com.github.exact7.xtra.model.kraken.user.Emote as TwitchEmote

const val MAX_ADAPTER_COUNT = 125
const val MAX_LIST_COUNT = MAX_ADAPTER_COUNT + 1

class ChatView : ConstraintLayout {

    interface MessageSenderCallback {
        fun send(message: CharSequence)
    }

    private val adapter = ChatAdapter(context.convertDpToPixels(29.5f), context.convertDpToPixels(18.5f))

    private var isChatTouched = false

    private var recentEmotes = emptyList<Emote>()
    private var twitchEmotes = emptyList<Emote>()
    private var otherEmotes = mutableSetOf<Emote>()
    private var emotesAddedCount = 0

    private var chatters = emptyList<Chatter>()
    private var autoCompleteAdapter: AutoCompleteAdapter? = null

    private lateinit var fragmentManager: FragmentManager
    private var messagingEnabled = false

    private var messageCallback: MessageSenderCallback? = null

    constructor(context: Context) : super(context) {
        init(context)
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init(context)
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(context)
    }

    init {
        val emotes: List<Emote> = ChatFragment.defaultBttvAndFfzEmotes()
        adapter.addEmotes(emotes)
        otherEmotes.addAll(emotes)
    }

    private fun init(context: Context) {
        View.inflate(context, R.layout.view_chat, this)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        recyclerView.apply {
            adapter = this@ChatView.adapter
            itemAnimator = null
            layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    isChatTouched = newState == RecyclerView.SCROLL_STATE_DRAGGING
                    this@ChatView.btnDown.isVisible = shouldShowButton()
                }
            })
        }

        btnDown.setOnClickListener {
            post {
                recyclerView.scrollToPosition(adapter.itemCount - 1)
                it.toggleVisibility()
            }
        }

        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
            } else {
                false
            }
        }
        editText.addTextChangedListener(onTextChanged = { text, _, _, _ ->
            if (text?.isNotBlank() == true) {
                send.visible()
                clear.visible()
            } else {
                send.gone()
                clear.gone()
            }
        })
        editText.setOnFocusChangeListener { _, hasFocus -> autoCompleteAdapter?.setNotifyOnChange(hasFocus) }
        clear.setOnClickListener {
            val text = editText.text.toString().trimEnd()
            editText.setText(text.substring(0, max(text.lastIndexOf(' '), 0)))
        }
        clear.setOnLongClickListener {
            editText.text.clear()
            true
        }
        send.setOnClickListener { sendMessage() }
    }

    fun submitList(list: MutableList<ChatMessage>) {
        adapter.messages = list
    }

    fun notifyMessageAdded() {
        adapter.messages?.apply {
            adapter.notifyItemInserted(lastIndex)
            if (size >= MAX_LIST_COUNT) {
                val removeCount = size - MAX_ADAPTER_COUNT
                adapter.messages?.apply {
                    repeat(removeCount) {
                        removeAt(0)
                    }
                }
                adapter.notifyItemRangeRemoved(0, removeCount)
            }
            if (!isChatTouched && btnDown.isGone) {
                recyclerView.scrollToPosition(lastIndex)
            }
        }
    }

    fun addChatter(chatter: Chatter) {
        autoCompleteAdapter //TODO need chatters list anyways because after orientation change
    }

    fun addEmotes(list: List<Emote>?) {
        list?.let {
            when (it.firstOrNull()) {
                is BttvEmote, is FfzEmote -> {
                    otherEmotes.addAll(it)
                    adapter.addEmotes(it)
                }
                is TwitchEmote -> twitchEmotes = it
                is RecentEmote -> recentEmotes = it
            }
        }
        if (++emotesAddedCount == 4 && messagingEnabled) {
            initEmotesViewPager()
            dataset = recentEmotes + twitchEmotes + otherEmotes + chatters
            editText.setAdapter(AutoCompleteAdapter(context, dataset).also { autoCompleteAdapter = it })
            editText.setTokenizer(SpaceTokenizer())
        } else if (emotesAddedCount > 4) {
            viewPager.adapter?.notifyDataSetChanged()
        }
    }

    fun setUsername(username: String) {
        adapter.setUsername(username)
    }

    fun setChatters(chatters: List<Chatter>) {
        this.chatters = chatters
    }

    fun setCallback(callback: MessageSenderCallback) {
        messageCallback = callback
    }

    fun hideEmotesMenu(): Boolean {
        return if (viewPager.isVisible) {
            viewPager.gone()
            true
        } else {
            false
        }
    }

    fun appendEmote(emote: Emote) {
        editText.text.append(emote.name).append(' ')
    }

    @SuppressLint("SetTextI18n")
    fun reply(userName: CharSequence) {
        val text = "@$userName "
        editText.apply {
            setText(text)
            setSelection(text.length)
            showKeyboard()
        }
    }

    fun setMessage(text: CharSequence) {
        editText.setText(text)
    }

    fun enableChatInteraction(enableMessaging: Boolean, fragmentManager: FragmentManager) {
        this.fragmentManager = fragmentManager
        adapter.setOnClickListener { original, formatted ->
            editText.hideKeyboard()
            MessageClickedDialog.newInstance(enableMessaging, original, formatted).show(fragmentManager, null)
        }
        if (enableMessaging) {
            messagingEnabled = true
            messageView.visible()
        }
    }

    private fun sendMessage(): Boolean {
        editText.hideKeyboard()
        editText.clearFocus()
        hideEmotesMenu()
        return messageCallback?.let {
            val text = editText.text.trim()
            editText.text.clear()
            if (text.isNotEmpty()) {
                it.send(text)
                true
            } else {
                false
            }
        } == true
    }

    private fun initEmotesViewPager() {
        viewPager.adapter = object : FragmentStatePagerAdapter(fragmentManager) {

            override fun getItem(position: Int): Fragment {
                val list = when (position) {
                    0 -> recentEmotes
                    1 -> twitchEmotes.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                    else -> otherEmotes.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                }
                return EmotesFragment.newInstance(list)
            }

            override fun getCount(): Int = 3

            override fun getPageTitle(position: Int): CharSequence? {
                return when (position) {
                    0 -> context.getString(R.string.recent_emotes)
                    1 -> "Twitch"
                    else -> "BTTV/FFZ"
                }
            }

            override fun getItemPosition(`object`: Any): Int {
                (`object` as EmotesFragment).run {
                    if (type == 0) {
                        setEmotes(recentEmotes)
                    }
                }
                return super.getItemPosition(`object`)
            }
        }
        viewPager.offscreenPageLimit = 2
        emotes.setOnClickListener {
            //TODO add animation
            with(viewPager) {
                if (isGone) {
                    if (recentEmotes.isEmpty() && currentItem == 0) {
                        setCurrentItem(1, false)
                    }
                    visible()
                } else {
                    gone()
                }
            }
        }
    }

    private fun shouldShowButton(): Boolean {
        val offset = recyclerView.computeVerticalScrollOffset()
        if (offset < 0) {
            return false
        }
        val extent = recyclerView.computeVerticalScrollExtent()
        val range = recyclerView.computeVerticalScrollRange()
        val percentage = (100f * offset / (range - extent).toFloat())
        return percentage < 97f
    }

    class SpaceTokenizer : MultiAutoCompleteTextView.Tokenizer {

        override fun findTokenStart(text: CharSequence, cursor: Int): Int {
            var i = cursor

            while (i > 0 && text[i - 1] != ' ') {
                i--
            }
            while (i < cursor && text[i] == ' ') {
                i++
            }

            return i
        }

        override fun findTokenEnd(text: CharSequence, cursor: Int): Int {
            var i = cursor
            val len = text.length

            while (i < len) {
                if (text[i] == ' ') {
                    return i
                } else {
                    i++
                }
            }

            return len
        }

        override fun terminateToken(text: CharSequence): CharSequence {
            var i = text.length

            while (i > 0 && text[i - 1] == ' ') {
                i--
            }

            return if (i > 0 && text[i - 1] == ' ') {
                text.substring(1)
            } else {
                if (text is Spanned) {
                    val sp = SpannableString("$text ")
                    TextUtils.copySpansFrom(text, 0, text.length, Any::class.java, sp, 0)
                    sp.substring(1)
                } else {
                    "${text.substring(1)} "
                }
            }
        }
    }

    class AutoCompleteAdapter(context: Context, list: List<Any>) : ArrayAdapter<Any>(context, 0, list) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view: View
            val viewHolder: ViewHolder
            val item = getItem(position)!!
            val isEmote = item is Emote
            if (convertView == null) {
                view = LayoutInflater.from(context).inflate(if (isEmote) R.layout.auto_complete_emotes_list_item else android.R.layout.simple_list_item_1, parent, false)
                viewHolder = ViewHolder(view).also { view.tag = it }
            } else {
                view = convertView
                viewHolder = convertView.tag as ViewHolder
            }
            return (viewHolder.containerView).apply {
                if (isEmote) {
                    item as Emote
                    image.loadImage(item.url)
                    name.text = item.name
                } else {
                    (this as TextView).text = (item as Chatter).name
                }
            }
        }

        class ViewHolder(override val containerView: View) : LayoutContainer
    }
}
