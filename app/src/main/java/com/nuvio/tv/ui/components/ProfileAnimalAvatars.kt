package com.nuvio.tv.ui.components

data class ProfileAnimalAvatar(
    val id: String,
    val label: String
) {
    val assetKey: String get() = "avatar_${id.removePrefix("animal:")}"
}

/** NAS-hosted profile avatars. */
val PROFILE_ANIMAL_AVATARS = listOf(
    ProfileAnimalAvatar("animal:aigle", "Aigle"),
    ProfileAnimalAvatar("animal:baleine", "Baleine"),
    ProfileAnimalAvatar("animal:cerf", "Cerf"),
    ProfileAnimalAvatar("animal:chat", "Chat"),
    ProfileAnimalAvatar("animal:cheval", "Cheval"),
    ProfileAnimalAvatar("animal:chevre", "Chèvre"),
    ProfileAnimalAvatar("animal:chien", "Chien"),
    ProfileAnimalAvatar("animal:dauphin", "Dauphin"),
    ProfileAnimalAvatar("animal:ecureuil", "Écureuil"),
    ProfileAnimalAvatar("animal:elephant", "Éléphant"),
    ProfileAnimalAvatar("animal:girafe", "Girafe"),
    ProfileAnimalAvatar("animal:herisson", "Hérisson"),
    ProfileAnimalAvatar("animal:hippopotame", "Hippopotame"),
    ProfileAnimalAvatar("animal:kangourou", "Kangourou"),
    ProfileAnimalAvatar("animal:koala", "Koala"),
    ProfileAnimalAvatar("animal:lapin", "Lapin"),
    ProfileAnimalAvatar("animal:lion", "Lion"),
    ProfileAnimalAvatar("animal:loup", "Loup"),
    ProfileAnimalAvatar("animal:mouton", "Mouton"),
    ProfileAnimalAvatar("animal:ours", "Ours"),
    ProfileAnimalAvatar("animal:panda", "Panda"),
    ProfileAnimalAvatar("animal:perroquet", "Perroquet"),
    ProfileAnimalAvatar("animal:renard", "Renard"),
    ProfileAnimalAvatar("animal:requin", "Requin"),
    ProfileAnimalAvatar("animal:rhinoceros", "Rhinocéros"),
    ProfileAnimalAvatar("animal:sanglier", "Sanglier"),
    ProfileAnimalAvatar("animal:tigre", "Tigre"),
    ProfileAnimalAvatar("animal:tortue", "Tortue"),
    ProfileAnimalAvatar("animal:vache", "Vache"),
    ProfileAnimalAvatar("animal:zebre", "Zèbre")
)

fun profileAnimalAvatarFromId(avatarId: String?): ProfileAnimalAvatar? =
    PROFILE_ANIMAL_AVATARS.firstOrNull { it.id == avatarId }
