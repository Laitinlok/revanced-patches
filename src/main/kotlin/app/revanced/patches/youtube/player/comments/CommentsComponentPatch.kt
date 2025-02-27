package app.revanced.patches.youtube.player.comments

import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.addInstruction
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patches.shared.fingerprints.SpannableStringBuilderFingerprint
import app.revanced.patches.shared.litho.LithoFilterPatch
import app.revanced.patches.shared.spans.InclusiveSpanPatch
import app.revanced.patches.youtube.player.comments.fingerprints.ShortsLiveStreamEmojiPickerOnClickListenerFingerprint
import app.revanced.patches.youtube.player.comments.fingerprints.ShortsLiveStreamEmojiPickerOpacityFingerprint
import app.revanced.patches.youtube.utils.compatibility.Constants.COMPATIBLE_PACKAGE
import app.revanced.patches.youtube.utils.integrations.Constants.COMPONENTS_PATH
import app.revanced.patches.youtube.utils.integrations.Constants.PLAYER_CLASS_DESCRIPTOR
import app.revanced.patches.youtube.utils.integrations.Constants.SPANS_PATH
import app.revanced.patches.youtube.utils.resourceid.SharedResourceIdPatch
import app.revanced.patches.youtube.utils.settings.SettingsPatch
import app.revanced.util.getWalkerMethod
import app.revanced.util.indexOfFirstInstructionOrThrow
import app.revanced.util.indexOfFirstWideLiteralInstructionValueOrThrow
import app.revanced.util.patch.BaseBytecodePatch
import app.revanced.util.resultOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

@Suppress("unused")
object CommentsComponentPatch : BaseBytecodePatch(
    name = "Hide comments components",
    description = "Adds options to hide components related to comments.",
    dependencies = setOf(
        InclusiveSpanPatch::class,
        LithoFilterPatch::class,
        SettingsPatch::class,
        SharedResourceIdPatch::class,
    ),
    compatiblePackages = COMPATIBLE_PACKAGE,
    fingerprints = setOf(
        ShortsLiveStreamEmojiPickerOnClickListenerFingerprint,
        ShortsLiveStreamEmojiPickerOpacityFingerprint,
        SpannableStringBuilderFingerprint,
    )
) {
    private const val COMMENTS_FILTER_CLASS_DESCRIPTOR =
        "$COMPONENTS_PATH/CommentsFilter;"
    private const val SEARCH_LINKS_FILTER_CLASS_DESCRIPTOR =
        "$SPANS_PATH/SearchLinksFilter;"

    override fun execute(context: BytecodeContext) {

        // region patch for emoji picker button in shorts

        ShortsLiveStreamEmojiPickerOpacityFingerprint.resultOrThrow().let {
            it.mutableMethod.apply {
                val insertIndex = implementation!!.instructions.size - 1
                val insertRegister = getInstruction<OneRegisterInstruction>(insertIndex).registerA

                addInstruction(
                    insertIndex,
                    "invoke-static {v$insertRegister}, $PLAYER_CLASS_DESCRIPTOR->changeEmojiPickerOpacity(Landroid/widget/ImageView;)V"
                )
            }
        }

        ShortsLiveStreamEmojiPickerOnClickListenerFingerprint.resultOrThrow().let {
            it.mutableMethod.apply {
                val emojiPickerEndpointIndex =
                    indexOfFirstWideLiteralInstructionValueOrThrow(126326492)
                val emojiPickerOnClickListenerIndex =
                    indexOfFirstInstructionOrThrow(emojiPickerEndpointIndex, Opcode.INVOKE_DIRECT)
                val emojiPickerOnClickListenerMethod =
                    getWalkerMethod(context, emojiPickerOnClickListenerIndex)

                emojiPickerOnClickListenerMethod.apply {
                    val insertIndex = indexOfFirstInstructionOrThrow(Opcode.IF_EQZ)
                    val insertRegister =
                        getInstruction<OneRegisterInstruction>(insertIndex).registerA

                    addInstructions(
                        insertIndex, """
                            invoke-static {v$insertRegister}, $PLAYER_CLASS_DESCRIPTOR->disableEmojiPickerOnClickListener(Ljava/lang/Object;)Ljava/lang/Object;
                            move-result-object v$insertRegister
                            """
                    )
                }
            }
        }

        // endregion

        InclusiveSpanPatch.addFilter(SEARCH_LINKS_FILTER_CLASS_DESCRIPTOR)
        LithoFilterPatch.addFilter(COMMENTS_FILTER_CLASS_DESCRIPTOR)

        /**
         * Add settings
         */
        SettingsPatch.addPreference(
            arrayOf(
                "PREFERENCE_SCREEN: PLAYER",
                "SETTINGS: HIDE_COMMENTS_COMPONENTS"
            )
        )

        SettingsPatch.updatePatchStatus(this)
    }
}
