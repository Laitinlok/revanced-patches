package app.revanced.patches.youtube.utils.returnyoutubedislike.general

import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.fingerprint.MethodFingerprint
import app.revanced.patches.shared.litho.LithoFilterPatch
import app.revanced.patches.shared.textcomponent.TextComponentPatch
import app.revanced.patches.youtube.utils.compatibility.Constants.COMPATIBLE_PACKAGE
import app.revanced.patches.youtube.utils.integrations.Constants.COMPONENTS_PATH
import app.revanced.patches.youtube.utils.integrations.Constants.UTILS_PATH
import app.revanced.patches.youtube.utils.returnyoutubedislike.general.fingerprints.DislikeFingerprint
import app.revanced.patches.youtube.utils.returnyoutubedislike.general.fingerprints.LikeFingerprint
import app.revanced.patches.youtube.utils.returnyoutubedislike.general.fingerprints.RemoveLikeFingerprint
import app.revanced.patches.youtube.utils.returnyoutubedislike.rollingnumber.ReturnYouTubeDislikeRollingNumberPatch
import app.revanced.patches.youtube.utils.returnyoutubedislike.shorts.ReturnYouTubeDislikeShortsPatch
import app.revanced.patches.youtube.utils.settings.SettingsPatch
import app.revanced.patches.youtube.video.information.VideoInformationPatch
import app.revanced.patches.youtube.video.videoid.VideoIdPatch
import app.revanced.util.patch.BaseBytecodePatch
import app.revanced.util.resultOrThrow

@Suppress("unused")
object ReturnYouTubeDislikePatch : BaseBytecodePatch(
    name = "Return YouTube Dislike",
    description = "Adds an option to show the dislike count of videos using the Return YouTube Dislike API.",
    dependencies = setOf(
        LithoFilterPatch::class,
        ReturnYouTubeDislikeRollingNumberPatch::class,
        ReturnYouTubeDislikeShortsPatch::class,
        SettingsPatch::class,
        TextComponentPatch::class,
        VideoInformationPatch::class
    ),
    compatiblePackages = COMPATIBLE_PACKAGE,
    fingerprints = setOf(
        DislikeFingerprint,
        LikeFingerprint,
        RemoveLikeFingerprint,
    )
) {
    private const val INTEGRATIONS_RYD_CLASS_DESCRIPTOR =
        "$UTILS_PATH/ReturnYouTubeDislikePatch;"

    private const val FILTER_CLASS_DESCRIPTOR =
        "$COMPONENTS_PATH/ReturnYouTubeDislikeFilterPatch;"

    override fun execute(context: BytecodeContext) {
        listOf(
            LikeFingerprint.toPatch(Vote.LIKE),
            DislikeFingerprint.toPatch(Vote.DISLIKE),
            RemoveLikeFingerprint.toPatch(Vote.REMOVE_LIKE)
        ).forEach { (fingerprint, vote) ->
            fingerprint.resultOrThrow().mutableMethod.addInstructions(
                0, """
                    const/4 v0, ${vote.value}
                    invoke-static {v0}, $INTEGRATIONS_RYD_CLASS_DESCRIPTOR->sendVote(I)V
                    """
            )
        }

        TextComponentPatch.hookTextComponent(INTEGRATIONS_RYD_CLASS_DESCRIPTOR)

        // region Inject newVideoLoaded event handler to update dislikes when a new video is loaded.
        VideoIdPatch.hookVideoId("$INTEGRATIONS_RYD_CLASS_DESCRIPTOR->newVideoLoaded(Ljava/lang/String;)V")

        // Hook the player response video id, to start loading RYD sooner in the background.
        VideoIdPatch.hookPlayerResponseVideoId("$INTEGRATIONS_RYD_CLASS_DESCRIPTOR->preloadVideoId(Ljava/lang/String;Z)V")

        // endregion

        // Player response video id is needed to search for the video ids in Shorts litho components.
        if (SettingsPatch.upward1834) {
            LithoFilterPatch.addFilter(FILTER_CLASS_DESCRIPTOR)
            VideoIdPatch.hookPlayerResponseVideoId("$FILTER_CLASS_DESCRIPTOR->newPlayerResponseVideoId(Ljava/lang/String;Z)V")
            VideoInformationPatch.hookShorts("$FILTER_CLASS_DESCRIPTOR->newShortsVideoStarted(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JZ)V")
        }

        // endregion

        /**
         * Add settings
         */
        SettingsPatch.addPreference(
            arrayOf(
                "PREFERENCE_SCREEN: RETURN_YOUTUBE_DISLIKE"
            )
        )

        SettingsPatch.updatePatchStatus(this)

    }

    private fun MethodFingerprint.toPatch(voteKind: Vote) = VotePatch(this, voteKind)

    private data class VotePatch(val fingerprint: MethodFingerprint, val voteKind: Vote)

    private enum class Vote(val value: Int) {
        LIKE(1),
        DISLIKE(-1),
        REMOVE_LIKE(0)
    }
}
