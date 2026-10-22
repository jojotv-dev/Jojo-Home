package com.nuvio.tv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioColors
import com.nuvio.tv.data.remote.nas.NasAvatarCatalogItem
import coil3.compose.AsyncImage

private val profileAvatarColors = listOf(
    "#1565C0", "#1E88E5", "#039BE5", "#00ACC1", "#00897B", "#2E7D32",
    "#43A047", "#7CB342", "#C0CA33", "#FDD835", "#D4AF37", "#D89B16", "#FFB300", "#FB8C00",
    "#F4511E", "#E53935", "#D81B60", "#C2185B", "#8E24AA", "#5E35B1",
    "#3949AB", "#546E7A", "#6D4C41", "#8D6E63", "#78909C", "#00695C"
)

fun profileAvatarEmojiFromId(avatarId: String?): String? =
    avatarId
        ?.takeIf { it.startsWith(PROFILE_EMOJI_AVATAR_PREFIX) }
        ?.removePrefix(PROFILE_EMOJI_AVATAR_PREFIX)
        ?.takeIf { it.isNotBlank() }

private val profileFaceEmojis = listOf(
    "😀", "😃", "😄", "😁", "😆", "😅",
    "😂", "🙂", "🙃", "😉", "😊", "😍",
    "🥰", "😘", "😎", "🤓", "🥳", "🤩",
    "😇", "🤠", "🥸", "🤗", "😴", "🤔"
)

private val profilePeopleEmojis = listOf(
    "👨", "👩", "👦", "👧",
    "👨🏻", "👩🏻", "👦🏻", "👧🏻",
    "👨🏼", "👩🏼", "👦🏼", "👧🏼",
    "👨🏽", "👩🏽", "👦🏽", "👧🏽",
    "👨🏾", "👩🏾", "👦🏾", "👧🏾",
    "👨🏿", "👩🏿", "👦🏿", "👧🏿"
)

private val profileFunnyCharacterEmojis = listOf(
    "🤡", "👻", "👽", "🤖", "💩", "👹",
    "👺", "😈", "😺", "😸", "😹", "😻",
    "🙀", "😼", "🙈", "🙉", "🙊", "🐵",
    "🐶", "🐱", "🦊", "🐼", "🦁", "🐸"
)

private enum class ProfileAvatarPickerTab { Colors, Emojis, People, Funny, Animals, Family }

@Composable
fun ProfileAvatarPalette(
    selectedColorHex: String,
    selectedAvatarId: String?,
    onColorSelected: (String) -> Unit,
    onEmojiSelected: (String) -> Unit,
    animalAvatars: List<NasAvatarCatalogItem>,
    onAnimalSelected: (NasAvatarCatalogItem) -> Unit,
    familyAvatars: List<NasAvatarCatalogItem>,
    onFamilySelected: (NasAvatarCatalogItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(ProfileAvatarPickerTab.Colors) }
    val colorsTabRequester = remember { FocusRequester() }
    val emojisTabRequester = remember { FocusRequester() }
    val peopleTabRequester = remember { FocusRequester() }
    val funnyTabRequester = remember { FocusRequester() }
    val animalsTabRequester = remember { FocusRequester() }
    val familyTabRequester = remember { FocusRequester() }
    val colorRequesters = remember {
        profileAvatarColors.associateWith { FocusRequester() }
    }
    val firstColorRequester = colorRequesters.getValue(profileAvatarColors.first())
    val emojiRequesters = remember { profileFaceEmojis.associateWith { FocusRequester() } }
    val peopleRequesters = remember { profilePeopleEmojis.associateWith { FocusRequester() } }
    val funnyRequesters = remember { profileFunnyCharacterEmojis.associateWith { FocusRequester() } }
    val animalRequesters = remember(animalAvatars) { animalAvatars.associate { it.id to FocusRequester() } }
    val familyRequesters = remember(familyAvatars) { familyAvatars.associate { it.id to FocusRequester() } }

    androidx.compose.foundation.layout.Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            ProfileAvatarPickerTabButton(
                label = "Couleurs",
                selected = selectedTab == ProfileAvatarPickerTab.Colors,
                focusRequester = colorsTabRequester,
                downFocusRequester = if (selectedTab == ProfileAvatarPickerTab.Colors) firstColorRequester else null,
                onClick = { selectedTab = ProfileAvatarPickerTab.Colors }
            )
            Spacer(Modifier.width(10.dp))
            ProfileAvatarPickerTabButton(
                label = "Émojis",
                selected = selectedTab == ProfileAvatarPickerTab.Emojis,
                focusRequester = emojisTabRequester,
                downFocusRequester = if (selectedTab == ProfileAvatarPickerTab.Emojis) emojiRequesters.getValue(profileFaceEmojis.first()) else null,
                onClick = { selectedTab = ProfileAvatarPickerTab.Emojis }
            )
            Spacer(Modifier.width(10.dp))
            ProfileAvatarPickerTabButton(
                label = "Personnes",
                selected = selectedTab == ProfileAvatarPickerTab.People,
                focusRequester = peopleTabRequester,
                downFocusRequester = if (selectedTab == ProfileAvatarPickerTab.People) peopleRequesters.getValue(profilePeopleEmojis.first()) else null,
                onClick = { selectedTab = ProfileAvatarPickerTab.People }
            )
            Spacer(Modifier.width(10.dp))
            ProfileAvatarPickerTabButton(
                label = "Rigolos",
                selected = selectedTab == ProfileAvatarPickerTab.Funny,
                focusRequester = funnyTabRequester,
                downFocusRequester = animalsTabRequester,
                onClick = { selectedTab = ProfileAvatarPickerTab.Funny }
            )
            Spacer(Modifier.width(10.dp))
            ProfileAvatarPickerTabButton(
                label = "Animaux",
                selected = selectedTab == ProfileAvatarPickerTab.Animals,
                focusRequester = animalsTabRequester,
                downFocusRequester = animalAvatars.firstOrNull()?.let { first -> if (selectedTab == ProfileAvatarPickerTab.Animals) animalRequesters.getValue(first.id) else null },
                onClick = { selectedTab = ProfileAvatarPickerTab.Animals }
            )
            Spacer(Modifier.width(10.dp))
            ProfileAvatarPickerTabButton(
                label = "Famille",
                selected = selectedTab == ProfileAvatarPickerTab.Family,
                focusRequester = familyTabRequester,
                downFocusRequester = familyAvatars.firstOrNull()?.let { first -> if (selectedTab == ProfileAvatarPickerTab.Family) familyRequesters.getValue(first.id) else null },
                onClick = { selectedTab = ProfileAvatarPickerTab.Family }
            )
        }

        when (selectedTab) {
            ProfileAvatarPickerTab.Colors -> LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                itemsIndexed(profileAvatarColors, key = { _, color -> color }) { index, colorHex ->
                    ProfileAvatarColorItem(
                        colorHex = colorHex,
                        selected = colorHex.equals(selectedColorHex, ignoreCase = true),
                        focusRequester = colorRequesters.getValue(colorHex),
                        upFocusRequester = if (index < 6) colorsTabRequester else null,
                        onClick = { onColorSelected(colorHex) }
                    )
                }
            }
            ProfileAvatarPickerTab.Emojis -> ProfileAvatarEmojiGrid(
                emojis = profileFaceEmojis,
                selectedAvatarId = selectedAvatarId,
                requesters = emojiRequesters,
                upFocusRequester = emojisTabRequester,
                onEmojiSelected = onEmojiSelected
            )
            ProfileAvatarPickerTab.People -> ProfileAvatarEmojiGrid(
                emojis = profilePeopleEmojis,
                selectedAvatarId = selectedAvatarId,
                requesters = peopleRequesters,
                upFocusRequester = peopleTabRequester,
                onEmojiSelected = onEmojiSelected
            )
            ProfileAvatarPickerTab.Funny -> ProfileAvatarEmojiGrid(
                emojis = profileFunnyCharacterEmojis,
                selectedAvatarId = selectedAvatarId,
                requesters = funnyRequesters,
                upFocusRequester = funnyTabRequester,
                onEmojiSelected = onEmojiSelected
            )
            ProfileAvatarPickerTab.Animals -> ProfileAnimalAvatarGrid(
                animals = animalAvatars,
                selectedAvatarId = selectedAvatarId,
                requesters = animalRequesters,
                upFocusRequester = animalsTabRequester,
                onAnimalSelected = onAnimalSelected
            )
            ProfileAvatarPickerTab.Family -> ProfileAnimalAvatarGrid(
                animals = familyAvatars,
                selectedAvatarId = selectedAvatarId,
                requesters = familyRequesters,
                upFocusRequester = familyTabRequester,
                onAnimalSelected = onFamilySelected
            )
        }
    }
}

@Composable
private fun ColumnScope.ProfileAnimalAvatarGrid(
    animals: List<NasAvatarCatalogItem>,
    selectedAvatarId: String?,
    requesters: Map<String, FocusRequester>,
    upFocusRequester: FocusRequester,
    onAnimalSelected: (NasAvatarCatalogItem) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().weight(1f)
    ) {
        itemsIndexed(animals, key = { _, animal -> animal.id }) { index, animal ->
            ProfileAnimalAvatarItem(
                animal = animal,
                selected = selectedAvatarId == animal.id,
                focusRequester = requesters.getValue(animal.id),
                upFocusRequester = if (index < 6) upFocusRequester else null,
                onClick = { onAnimalSelected(animal) }
            )
        }
    }
}

@Composable
private fun ProfileAnimalAvatarItem(
    animal: NasAvatarCatalogItem,
    selected: Boolean,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val borderWidth by animateDpAsState(if (focused) 3.dp else if (selected) 2.dp else 0.dp, tween(120), label = "animalAvatarBorderWidth")
    Box(
        modifier = Modifier.requiredSize(54.dp).focusRequester(focusRequester)
            .then(if (upFocusRequester != null) Modifier.focusProperties { up = upFocusRequester } else Modifier)
            .clip(CircleShape).border(borderWidth, if (focused) NuvioColors.FocusRing else Color.White.copy(alpha = 0.75f), CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (animal.imageUrl.isBlank()) {
            Text(
                text = animal.displayName.firstOrNull()?.toString().orEmpty(),
                fontSize = 29.sp,
                fontWeight = FontWeight.Medium
            )
        } else {
            AsyncImage(
                model = animal.imageUrl,
                contentDescription = animal.displayName,
                modifier = Modifier.requiredSize(54.dp).padding(2.dp).clip(CircleShape),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun ColumnScope.ProfileAvatarEmojiGrid(
    emojis: List<String>,
    selectedAvatarId: String?,
    requesters: Map<String, FocusRequester>,
    upFocusRequester: FocusRequester,
    onEmojiSelected: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
    ) {
        itemsIndexed(emojis, key = { _, emoji -> emoji }) { index, emoji ->
            ProfileAvatarEmojiItem(
                emoji = emoji,
                selected = selectedAvatarId == "$PROFILE_EMOJI_AVATAR_PREFIX$emoji",
                focusRequester = requesters.getValue(emoji),
                upFocusRequester = if (index < 6) upFocusRequester else null,
                onClick = { onEmojiSelected(emoji) }
            )
        }
    }
}

@Composable
private fun ProfileAvatarEmojiItem(
    emoji: String,
    selected: Boolean,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val borderWidth by animateDpAsState(
        targetValue = if (focused) 3.dp else if (selected) 2.dp else 0.dp,
        animationSpec = tween(120),
        label = "profileAvatarEmojiBorderWidth"
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused) NuvioColors.FocusRing else Color.White.copy(alpha = if (selected) 0.75f else 0f),
        animationSpec = tween(120),
        label = "profileAvatarEmojiBorderColor"
    )

    Box(
        modifier = Modifier.fillMaxWidth().height(54.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .requiredSize(54.dp)
                .focusRequester(focusRequester)
                .then(if (upFocusRequester != null) Modifier.focusProperties { up = upFocusRequester } else Modifier)
                .clip(CircleShape)
                .background(NuvioColors.BackgroundCard)
                .border(borderWidth, borderColor, CircleShape)
                .onFocusChanged { focused = it.isFocused }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(text = emoji, fontSize = 29.sp)
        }
    }
}

@Composable
private fun ProfileAvatarPickerTabButton(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester,
    downFocusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val background by animateColorAsState(
        targetValue = if (focused) NuvioColors.Secondary else Color.Transparent,
        animationSpec = tween(150),
        label = "profileAvatarPickerTabBackground"
    )
    val textColor by animateColorAsState(
        targetValue = if (focused) NuvioColors.OnSecondary else NuvioColors.TextSecondary,
        animationSpec = tween(150),
        label = "profileAvatarPickerTabText"
    )

    Box(
        modifier = Modifier
            .focusRequester(focusRequester)
            .then(if (downFocusRequester != null) Modifier.focusProperties { down = downFocusRequester } else Modifier)
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 20.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
private fun ProfileAvatarColorItem(
    colorHex: String,
    selected: Boolean,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val borderWidth by animateDpAsState(
        targetValue = if (focused) 3.dp else if (selected) 2.dp else 0.dp,
        animationSpec = tween(120),
        label = "profileAvatarColorBorderWidth"
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused) NuvioColors.FocusRing else Color.White.copy(alpha = if (selected) 0.75f else 0f),
        animationSpec = tween(120),
        label = "profileAvatarColorBorderColor"
    )
    val fallbackColor = NuvioColors.BackgroundCard
    val color = remember(colorHex, fallbackColor) {
        runCatching { Color(android.graphics.Color.parseColor(colorHex)) }
            .getOrDefault(fallbackColor)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .requiredSize(54.dp)
                .focusRequester(focusRequester)
                .then(if (upFocusRequester != null) Modifier.focusProperties { up = upFocusRequester } else Modifier)
                .clip(CircleShape)
                .background(color)
                .border(borderWidth, borderColor, CircleShape)
                .onFocusChanged { focused = it.isFocused }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
        )
    }
}
