package app.revanced.patches.shared.fingerprints

import app.revanced.patcher.extensions.or
import app.revanced.patcher.fingerprint.MethodFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object StartVideoInformerFingerprint : MethodFingerprint(
    returnType = "V",
    accessFlags = AccessFlags.PUBLIC or AccessFlags.FINAL,
    opcodes = listOf(
        Opcode.INVOKE_INTERFACE,
        Opcode.RETURN_VOID
    ),
    strings = listOf("pc"),
    customFingerprint = custom@{ methodDef, _ ->
        if (methodDef.implementation == null)
            return@custom false

        methodDef.implementation!!.instructions
            .withIndex()
            .filter { (_, instruction) ->
                instruction.opcode == Opcode.CONST_STRING
            }
            .map { (index, _) -> index }
            .size == 1
    }
)