package app.halo.launcher.easter

import java.text.Normalizer

/** Halo's hidden extras: asked for by voice, the remote's Konami code or five presses of the star button. */
enum class Egg {
    SING, POD_BAY, BARREL_ROLL, SELF_DESTRUCT, MEANING, BEAM, SNOW, SKYNET, SECRET, HELLO_THERE,
    SANDWICH, SUDO_SANDWICH, COIN, DICE, PARTY, GOODNIGHT, DADDY, CREDITS, RETRO;

    /** The id the voice assistant's model uses. */
    val id: String get() = name.lowercase()

    /** True when the egg shows something on Halo's screen (Halo comes to the front for it). */
    val visual: Boolean get() = this in setOf(SING, BARREL_ROLL, SELF_DESTRUCT, BEAM, SNOW, SKYNET, COIN, DICE, PARTY, GOODNIGHT, CREDITS, RETRO)

    companion object {
        fun of(id: String?): Egg? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }
    }
}

/** One playing egg. [value]: the coin side (0/1), the die face (1–6) or the snow's duration in seconds. */
data class EggEvent(val egg: Egg, val id: Long = System.nanoTime(), val value: Int = 0)

/** Recognises the egg phrases in English, German and Turkish (short requests only, so real commands win). */
object EggMatcher {
    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace('ı', 'i')
        .replace(Regex("[^a-z0-9ß ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private val rules: List<Pair<Egg, Regex>> = listOf(
        Egg.SUDO_SANDWICH to Regex("\\bsudo\\b.*\\b(sandwich|sandvic|stulle|brot)"),
        Egg.SANDWICH to Regex("\\b(make me a sandwich|mach mir (ein|n) (sandwich|brot)|bana (bir )?sandvic)"),
        Egg.MEANING to Regex("\\b(meaning of life|sinn des lebens|hayatin anlami)"),
        Egg.POD_BAY to Regex("\\b(pod ?bay|kapseltur|kapsel tur)"),
        Egg.BARREL_ROLL to Regex("\\b(barrel ?roll|fass ?rolle|fiçi|fici|takla at)"),
        Egg.SELF_DESTRUCT to Regex("\\b(self ?destruct|selbstzerstorung|selbst zerstorung|kendini imha|imha et)"),
        Egg.BEAM to Regex("\\b(beam me up|beam mich|isinla beni|isinla)"),
        Egg.SNOW to Regex("\\b(let it snow|make it snow|lass es schneien|schneien lassen|kar yagdir|kar yagsin)"),
        Egg.SKYNET to Regex("\\bskynet"),
        Egg.SECRET to Regex("\\b(tell me a secret|a secret|verrat mir ein geheimnis|ein geheimnis|bana bir sir|sir soyle|sirrini)"),
        Egg.HELLO_THERE to Regex("^(well )?hello there$"),
        Egg.COIN to Regex("\\b(flip a coin|toss a coin|coin flip|heads or tails|wirf eine munze|munze werfen|munzwurf|kopf oder zahl|yazi tura|para at)"),
        Egg.DICE to Regex("\\b(roll a die|roll a dice|roll the dice|roll dice|throw a die|wurfel\\w*|zar at|zar)\\b"),
        Egg.PARTY to Regex("\\b(party mode|party time|lets party|partymodus|party modus|partyzeit|parti modu|parti zamani|party)\\b"),
        Egg.GOODNIGHT to Regex("\\b(good ?night|gute nacht|iyi geceler)"),
        Egg.DADDY to Regex("\\b(who ?s your daddy|who is your daddy|who made you|who built you|who created you|wer ist dein papa|wer ist dein vater|wer hat dich (gebaut|gemacht|erschaffen)|baban kim|seni kim (yapti|yaratti))"),
        Egg.CREDITS to Regex("\\b(show (the )?credits|abspann|zeig (die )?credits|jenerik)"),
        Egg.RETRO to Regex("\\b(retro mode|retro modus|retromodus|retro modu)"),
        Egg.SING to Regex("\\b(sing|singe|singen|sarki soyle|sarki soyler|daisy)\\b"),
    )

    fun match(text: String): Egg? {
        val n = norm(text)
        if (n.isEmpty() || n.length > 60) return null
        // "Play 'Let it snow' on Spotify", "sing along in the Karaoke app": real requests, not eggs.
        if (Regex("\\b(play|spiel|spiele|oynat|youtube|spotify|app|song by|lied von)\\b").containsMatchIn(n) &&
            !n.contains("sudo")
        ) return null
        return rules.firstOrNull { (_, r) -> r.containsMatchIn(n) }?.first
    }
}
