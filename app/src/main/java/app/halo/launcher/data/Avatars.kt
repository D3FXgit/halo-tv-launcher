package app.halo.launcher.data

import androidx.annotation.DrawableRes
import app.halo.launcher.R

/**
 * Profile picture library: Microsoft Fluent Emoji 3D (MIT licence), bundled offline.
 * Licence text: FLUENT-EMOJI-LICENSE.txt in the project root.
 */
object Avatars {
    data class Avatar(val id: String, @DrawableRes val res: Int, val name: String)

    val all: List<Avatar> = listOf(
        Avatar("fox", R.drawable.avatar_fox, "Fox"),
        Avatar("cat", R.drawable.avatar_cat_face, "Cat"),
        Avatar("dog", R.drawable.avatar_dog_face, "Dog"),
        Avatar("panda", R.drawable.avatar_panda, "Panda"),
        Avatar("koala", R.drawable.avatar_koala, "Koala"),
        Avatar("lion", R.drawable.avatar_lion, "Lion"),
        Avatar("tiger", R.drawable.avatar_tiger_face, "Tiger"),
        Avatar("bear", R.drawable.avatar_bear, "Bear"),
        Avatar("rabbit", R.drawable.avatar_rabbit_face, "Rabbit"),
        Avatar("frog", R.drawable.avatar_frog, "Frog"),
        Avatar("owl", R.drawable.avatar_owl, "Owl"),
        Avatar("penguin", R.drawable.avatar_penguin, "Penguin"),
        Avatar("unicorn", R.drawable.avatar_unicorn, "Unicorn"),
        Avatar("octopus", R.drawable.avatar_octopus, "Octopus"),
        Avatar("dragon", R.drawable.avatar_dragon_face, "Dragon"),
        Avatar("alien", R.drawable.avatar_alien, "Alien"),
        Avatar("robot", R.drawable.avatar_robot, "Robot"),
        Avatar("ghost", R.drawable.avatar_ghost, "Ghost"),
        Avatar("cool", R.drawable.avatar_smiling_face_with_sunglasses, "Cool"),
        Avatar("stars", R.drawable.avatar_star_struck, "Star-struck"),
        Avatar("cowboy", R.drawable.avatar_cowboy_hat_face, "Cowboy"),
        Avatar("rocket", R.drawable.avatar_rocket, "Rocket"),
        Avatar("tree", R.drawable.avatar_evergreen_tree, "Forest"),
        Avatar("sun", R.drawable.avatar_sun_with_face, "Sun"),
        Avatar("moon", R.drawable.avatar_full_moon_face, "Moon"),
        Avatar("rainbow", R.drawable.avatar_rainbow, "Rainbow"),
        Avatar("game", R.drawable.avatar_video_game, "Gamer"),
        Avatar("football", R.drawable.avatar_soccer_ball, "Football"),
        Avatar("guitar", R.drawable.avatar_guitar, "Guitar"),
        Avatar("popcorn", R.drawable.avatar_popcorn, "Popcorn"),
        Avatar("pizza", R.drawable.avatar_pizza, "Pizza"),
        Avatar("doughnut", R.drawable.avatar_doughnut, "Doughnut"),
    )

    fun of(id: String?): Avatar? = all.firstOrNull { it.id == id }
}
