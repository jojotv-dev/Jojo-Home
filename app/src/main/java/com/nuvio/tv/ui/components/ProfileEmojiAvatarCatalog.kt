package com.nuvio.tv.ui.components

const val PROFILE_EMOJI_AVATAR_PREFIX = "emoji:"

data class ProfileEmojiAvatarCatalogEntry(
    val emoji: String,
    val category: String,
    val displayName: String,
    val assetKey: String,
    val sortOrder: Int
) {
    val id: String = "$PROFILE_EMOJI_AVATAR_PREFIX$emoji"
}

private val profileEmojiGroups = listOf(
    "emojis" to listOf("😀", "😃", "😄", "😁", "😆", "😅", "😂", "🙂", "🙃", "😉", "😊", "😍", "🥰", "😘", "😎", "🤓", "🥳", "🤩", "😇", "🤠", "🥸", "🤗", "😴", "🤔"),
    "people" to listOf("👨", "👩", "👦", "👧", "👨🏻", "👩🏻", "👦🏻", "👧🏻", "👨🏼", "👩🏼", "👦🏼", "👧🏼", "👨🏽", "👩🏽", "👦🏽", "👧🏽", "👨🏾", "👩🏾", "👦🏾", "👧🏾", "👨🏿", "👩🏿", "👦🏿", "👧🏿"),
    "funny" to listOf("🤡", "👻", "👽", "🤖", "💩", "👹", "👺", "😈", "😺", "😸", "😹", "😻", "🙀", "😼", "🙈", "🙉", "🙊", "🐵", "🐶", "🐱", "🦊", "🐼", "🦁", "🐸")
)

val PROFILE_EMOJI_AVATAR_CATALOG: List<ProfileEmojiAvatarCatalogEntry> = buildList {
    var order = 2_000
    profileEmojiGroups.forEach { (category, emojis) ->
        emojis.forEachIndexed { index, emoji ->
            add(ProfileEmojiAvatarCatalogEntry(emoji, category, emoji, "avatar_${category}_$index", order++))
        }
    }
}
