package app.halo.launcher.easter

import android.content.Context
import app.halo.launcher.R
import app.halo.launcher.i18n.Lang

/** What Halo says for each Easter egg, in its interface language. */
object EggLines {
    fun line(context: Context, e: Egg, value: Int): String {
        fun s(id: Int, vararg a: Any) = Lang.string(context, id, *a)
        return when (e) {
            Egg.SING -> s(R.string.egg_sing_reply)
            Egg.POD_BAY -> s(R.string.egg_pod_bay)
            Egg.BARREL_ROLL -> s(R.string.egg_barrel_roll)
            Egg.SELF_DESTRUCT -> s(R.string.egg_self_destruct)
            Egg.MEANING -> s(R.string.egg_meaning)
            Egg.BEAM -> s(R.string.egg_beam)
            Egg.SNOW -> s(R.string.egg_snow)
            Egg.SKYNET -> s(R.string.egg_skynet)
            Egg.SECRET -> s(R.string.egg_secrets).split('|').map { it.trim() }.filter { it.isNotEmpty() }.random()
            Egg.HELLO_THERE -> s(R.string.egg_hello_there)
            Egg.SANDWICH -> s(R.string.egg_sandwich)
            Egg.SUDO_SANDWICH -> s(R.string.egg_sudo_sandwich)
            Egg.COIN -> s(if (value == 0) R.string.egg_heads else R.string.egg_tails)
            Egg.DICE -> s(R.string.egg_dice, value)
            Egg.PARTY -> s(R.string.egg_party)
            Egg.GOODNIGHT -> s(R.string.egg_goodnight)
            Egg.DADDY -> s(R.string.egg_daddy)
            Egg.CREDITS -> s(R.string.egg_credits)
            Egg.RETRO -> s(R.string.egg_retro_said_on)
        }
    }
}
