package app.revanced.patches.shared.opus

import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.patch.BytecodePatch
import app.revanced.patcher.util.smali.ExternalLabel
import app.revanced.patches.shared.opus.fingerprints.CodecReferenceFingerprint
import app.revanced.patches.shared.opus.fingerprints.CodecSelectorFingerprint
import app.revanced.util.getReference
import app.revanced.util.indexOfFirstInstructionOrThrow
import app.revanced.util.resultOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * This patch is generally not required for the latest versions of YouTube and YouTube Music.
 * For YouTube Music, if user spoofs the app version to v4.27.53, mp4a codec is still used, this is the patch for some of these users.
 */
abstract class BaseOpusCodecsPatch(
    private val descriptor: String
) : BytecodePatch(
    setOf(
        CodecReferenceFingerprint,
        CodecSelectorFingerprint
    )
) {
    override fun execute(context: BytecodeContext) {

        val opusCodecReference = with(CodecReferenceFingerprint.resultOrThrow().mutableMethod) {
            val codecIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_STATIC &&
                        getReference<MethodReference>()?.returnType == "Ljava/util/Set;"
            }
            getInstruction<ReferenceInstruction>(codecIndex).reference
        }

        CodecSelectorFingerprint.resultOrThrow().let {
            it.mutableMethod.apply {
                val freeRegister = implementation!!.registerCount - parameters.size - 2
                val targetIndex = it.scanResult.patternScanResult!!.endIndex
                val targetRegister = getInstruction<OneRegisterInstruction>(targetIndex).registerA

                addInstructionsWithLabels(
                    targetIndex + 1, """
                        invoke-static {}, $descriptor
                        move-result v$freeRegister
                        if-eqz v$freeRegister, :mp4a
                        invoke-static {}, $opusCodecReference
                        move-result-object v$targetRegister
                        """, ExternalLabel("mp4a", getInstruction(targetIndex + 1))
                )
            }
        }
    }
}
