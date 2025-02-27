package app.revanced.patches.shared.gms

import app.revanced.patcher.PatchClass
import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.addInstruction
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.fingerprint.MethodFingerprint
import app.revanced.patcher.patch.BytecodePatch
import app.revanced.patcher.patch.PatchException
import app.revanced.patches.shared.gms.BaseGmsCoreSupportPatch.Constants.ACTIONS
import app.revanced.patches.shared.gms.BaseGmsCoreSupportPatch.Constants.AUTHORITIES
import app.revanced.patches.shared.gms.BaseGmsCoreSupportPatch.Constants.PERMISSIONS
import app.revanced.patches.shared.gms.BaseGmsCoreSupportResourcePatch.Companion.ORIGINAL_PACKAGE_NAME_YOUTUBE
import app.revanced.patches.shared.gms.BaseGmsCoreSupportResourcePatch.Companion.ORIGINAL_PACKAGE_NAME_YOUTUBE_MUSIC
import app.revanced.patches.shared.gms.fingerprints.CastContextFetchFingerprint
import app.revanced.patches.shared.gms.fingerprints.CertificateFingerprint
import app.revanced.patches.shared.gms.fingerprints.GmsCoreSupportFingerprint
import app.revanced.patches.shared.gms.fingerprints.GmsServiceBrokerFingerprint
import app.revanced.patches.shared.gms.fingerprints.GooglePlayUtilityFingerprint
import app.revanced.patches.shared.gms.fingerprints.PrimesApiFingerprint
import app.revanced.patches.shared.gms.fingerprints.PrimesBackgroundInitializationFingerprint
import app.revanced.patches.shared.gms.fingerprints.PrimesLifecycleEventFingerprint
import app.revanced.patches.shared.gms.fingerprints.ServiceCheckFingerprint
import app.revanced.patches.shared.integrations.Constants.PATCHES_PATH
import app.revanced.util.getReference
import app.revanced.util.indexOfFirstInstructionOrThrow
import app.revanced.util.indexOfFirstInstructionReversedOrThrow
import app.revanced.util.resultOrThrow
import app.revanced.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction21c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.util.MethodUtil

/**
 * A patch that allows patched Google apps to run without root and under a different package name
 * by using GmsCore instead of Google Play Services.
 *
 * @param fromPackageName The package name of the original app.
 * @param mainActivityOnCreateFingerprint The fingerprint of the main activity onCreate method.
 * @param integrationsPatchDependency The patch responsible for the integrations.
 * @param gmsCoreSupportResourcePatch The corresponding resource patch that is used to patch the resources.
 * @param dependencies Additional dependencies of this patch.
 * @param compatiblePackages The compatible packages of this patch.
 */
@Suppress("SameParameterValue")
abstract class BaseGmsCoreSupportPatch(
    private val fromPackageName: String,
    private val mainActivityOnCreateFingerprint: MethodFingerprint,
    private val integrationsPatchDependency: PatchClass,
    gmsCoreSupportResourcePatch: BaseGmsCoreSupportResourcePatch,
    dependencies: Set<PatchClass> = setOf(),
    compatiblePackages: Set<CompatiblePackage>? = null,
) : BytecodePatch(
    name = "GmsCore support",
    description = "Allows patched Google apps to run without root and under a different package name " +
            "by using GmsCore instead of Google Play Services.",
    dependencies = setOf(
        gmsCoreSupportResourcePatch::class,
        integrationsPatchDependency,
    ) + dependencies,
    compatiblePackages = compatiblePackages,
    fingerprints = setOf(
        // Google Play Services.
        CastContextFetchFingerprint,
        GmsCoreSupportFingerprint,
        GmsServiceBrokerFingerprint,
        GooglePlayUtilityFingerprint,
        PrimesApiFingerprint,
        PrimesBackgroundInitializationFingerprint,
        PrimesLifecycleEventFingerprint,
        ServiceCheckFingerprint,

        // Signature verification.
        CertificateFingerprint,

        // MainActivity.
        mainActivityOnCreateFingerprint,
    ),
    requiresIntegrations = true,
) {
    private companion object {
        const val INTEGRATIONS_CLASS_DESCRIPTOR =
            "$PATCHES_PATH/GmsCoreSupport;"

        var gmsCoreVendor = "app.revanced"
        var checkGmsCore = true
        var disableGmsServiceBroker = false
        var packageNameYouTube = "com.google.android.youtube"
        var packageNameYouTubeMusic = "com.google.android.apps.youtube.music"

    }

    init {
        // Manually register all options of the resource patch so that they are visible in the patch API.
        gmsCoreSupportResourcePatch.options.values.forEach(options::register)
    }

    private fun getBooleanPatchOption(optionKey: String): Boolean {
        this.options.values.forEach { options ->
            if (options.key == optionKey && options.value is Boolean)
                return options.value as Boolean
        }
        throw PatchException("Patch option not found: $optionKey")
    }

    private fun getStringPatchOption(optionKey: String): String {
        this.options.values.forEach { options ->
            if (options.key == optionKey)
                return options.value.toString()
        }
        throw PatchException("Patch option not found: $optionKey")
    }

    private fun getPackageName(originalPackageName: String): String {
        if (originalPackageName == ORIGINAL_PACKAGE_NAME_YOUTUBE) {
            return packageNameYouTube
        } else if (originalPackageName == ORIGINAL_PACKAGE_NAME_YOUTUBE_MUSIC) {
            return packageNameYouTubeMusic
        }
        throw PatchException("Unknown package name!")
    }

    override fun execute(context: BytecodeContext) {
        gmsCoreVendor = getStringPatchOption("GmsCoreVendorGroupId")
        checkGmsCore = getBooleanPatchOption("CheckGmsCore")
        disableGmsServiceBroker = getBooleanPatchOption("DisableGmsServiceBroker")
        packageNameYouTube = getStringPatchOption("PackageNameYouTube")
        packageNameYouTubeMusic = getStringPatchOption("PackageNameYouTubeMusic")

        val packageName = getPackageName(fromPackageName)

        // Transform all strings using all provided transforms, first match wins.
        val transformations = arrayOf(
            ::commonTransform,
            ::contentUrisTransform,
            packageNameTransform(fromPackageName, packageName),
        )

        context.transformStringReferences transform@{ string ->
            transformations.forEach { transform ->
                transform(string)?.let { transformedString -> return@transform transformedString }
            }

            return@transform null
        }

        // Return these methods early to prevent the app from crashing.
        val returnEarly = mutableListOf(
            CastContextFetchFingerprint,
            GooglePlayUtilityFingerprint,
            ServiceCheckFingerprint
        )
        if (disableGmsServiceBroker) {
            returnEarly += GmsServiceBrokerFingerprint
        }
        returnEarly.returnEarly()

        transformPrimeMethod()

        // Verify GmsCore is installed and whitelisted for power optimizations and background usage.
        if (checkGmsCore) {
            mainActivityOnCreateFingerprint.resultOrThrow().mutableMethod.addInstructions(
                1, // Hack to not disturb other patches (such as the YTMusic integrations patch).
                "invoke-static/range { p0 .. p0 }, $INTEGRATIONS_CLASS_DESCRIPTOR->" +
                        "checkGmsCore(Landroid/app/Activity;)V",
            )
        }

        // Change the vendor of GmsCore in ReVanced Integrations.
        GmsCoreSupportFingerprint
            .resultOrThrow()
            .mutableMethod
            .replaceInstruction(0, "const-string v0, \"$gmsCoreVendor\"")

        // Spoof signature.
        CertificateFingerprint.result?.mutableClass?.methods?.forEach { mutableMethod ->
            mutableMethod.apply {
                val getPackageNameIndex =
                    CertificateFingerprint.indexOfGetPackageNameInstruction(this)

                if (getPackageNameIndex > -1) {
                    val targetRegister =
                        (getInstruction(getPackageNameIndex) as FiveRegisterInstruction).registerC

                    replaceInstruction(
                        getPackageNameIndex,
                        "invoke-static {v$targetRegister}, $INTEGRATIONS_CLASS_DESCRIPTOR->spoofPackageName(Landroid/content/Context;)Ljava/lang/String;",
                    )
                }
            }
        } // Since it has only been confirmed to work on YouTube and YouTube Music, does not raise an exception even if the fingerprint cannot be solved.
    }

    private fun BytecodeContext.transformStringReferences(transform: (str: String) -> String?) =
        classes.forEach {
            val mutableClass by lazy {
                proxy(it).mutableClass
            }

            it.methods.forEach classLoop@{ methodDef ->
                val implementation = methodDef.implementation ?: return@classLoop

                val mutableMethod by lazy {
                    mutableClass.methods.first { method ->
                        MethodUtil.methodSignaturesMatch(
                            method,
                            methodDef
                        )
                    }
                }

                implementation.instructions.forEachIndexed insnLoop@{ index, instruction ->
                    val string =
                        ((instruction as? Instruction21c)?.reference as? StringReference)?.string
                            ?: return@insnLoop

                    // Apply transformation.
                    val transformedString = transform(string) ?: return@insnLoop

                    mutableMethod.replaceInstruction(
                        index,
                        BuilderInstruction21c(
                            Opcode.CONST_STRING,
                            instruction.registerA,
                            ImmutableStringReference(transformedString),
                        ),
                    )
                }
            }
        }

    // region Collection of transformations that are applied to all strings.

    private fun commonTransform(referencedString: String): String? =
        when (referencedString) {
            "com.google",
            "com.google.android.gms",
            in PERMISSIONS,
            in ACTIONS,
            in AUTHORITIES,
                -> referencedString.replace("com.google", gmsCoreVendor)

            // No vendor prefix for whatever reason...
            "subscribedfeeds" -> "$gmsCoreVendor.subscribedfeeds"
            else -> null
        }

    private fun contentUrisTransform(str: String): String? {
        // only when content:// uri
        if (str.startsWith("content://")) {
            // check if matches any authority
            for (authority in AUTHORITIES) {
                val uriPrefix = "content://$authority"
                if (str.startsWith(uriPrefix)) {
                    return str.replace(
                        uriPrefix,
                        "content://${authority.replace("com.google", gmsCoreVendor)}",
                    )
                }
            }

            // gms also has a 'subscribedfeeds' authority, check for that one too
            val subFeedsUriPrefix = "content://subscribedfeeds"
            if (str.startsWith(subFeedsUriPrefix)) {
                return str.replace(
                    subFeedsUriPrefix,
                    "content://$gmsCoreVendor.subscribedfeeds"
                )
            }
        }

        return null
    }

    private fun packageNameTransform(
        fromPackageName: String,
        toPackageName: String
    ): (String) -> String? = { string ->
        when (string) {
            "$fromPackageName.SuggestionsProvider",
            "$fromPackageName.fileprovider",
                -> string.replace(fromPackageName, toPackageName)

            else -> null
        }
    }

    private fun transformPrimeMethod() {
        listOf(
            PrimesBackgroundInitializationFingerprint,
            PrimesLifecycleEventFingerprint
        ).forEach { fingerprint ->
            fingerprint.resultOrThrow().mutableMethod.apply {
                val exceptionIndex = indexOfFirstInstructionReversedOrThrow {
                    opcode == Opcode.NEW_INSTANCE &&
                            (this as? ReferenceInstruction)?.reference?.toString() == "Ljava/lang/IllegalStateException;"
                }
                val index = indexOfFirstInstructionReversedOrThrow(exceptionIndex, Opcode.IF_EQZ)
                val register = getInstruction<OneRegisterInstruction>(index).registerA
                addInstruction(
                    index,
                    "const/4 v$register, 0x1"
                )
            }
        }
        PrimesApiFingerprint.resultOrThrow().let {
            it.mutableClass.methods.filter { method ->
                method.name != "<clinit>" &&
                        method.returnType == "V"
            }.forEach { method ->
                method.apply {
                    val index = if (MethodUtil.isConstructor(method))
                        indexOfFirstInstructionOrThrow {
                            opcode == Opcode.INVOKE_DIRECT &&
                                    getReference<MethodReference>()?.name == "<init>"
                        } + 1
                    else 0
                    addInstruction(
                        index,
                        "return-void"
                    )
                }
            }
        }
    }

    /**
     * A collection of permissions, intents and content provider authorities
     * that are present in GmsCore which need to be transformed.
     *
     * NOTE: The following were present, but it seems like they are not needed to be transformed:
     * - com.google.android.gms.chimera.GmsIntentOperationService
     * - com.google.android.gms.phenotype.internal.IPhenotypeCallbacks
     * - com.google.android.gms.phenotype.internal.IPhenotypeService
     * - com.google.android.gms.phenotype.PACKAGE_NAME
     * - com.google.android.gms.phenotype.UPDATE
     * - com.google.android.gms.phenotype
     */
    private object Constants {
        /**
         * A list of all permissions.
         */
        val PERMISSIONS = setOf(
            // C2DM / GCM
            "com.google.android.c2dm.permission.RECEIVE",
            "com.google.android.c2dm.permission.SEND",
            "com.google.android.gtalkservice.permission.GTALK_SERVICE",
            "com.google.android.providers.gsf.permission.READ_GSERVICES",

            // GAuth
            "com.google.android.googleapps.permission.GOOGLE_AUTH",
            "com.google.android.googleapps.permission.GOOGLE_AUTH.cp",
            "com.google.android.googleapps.permission.GOOGLE_AUTH.local",
            "com.google.android.googleapps.permission.GOOGLE_AUTH.mail",
            "com.google.android.googleapps.permission.GOOGLE_AUTH.writely",

            // Ad
            "com.google.android.gms.permission.AD_ID_NOTIFICATION",
            "com.google.android.gms.permission.AD_ID",
        )

        /**
         * All intent actions.
         */
        val ACTIONS = setOf(
            // location
            "com.google.android.gms.location.places.ui.PICK_PLACE",
            "com.google.android.gms.location.places.GeoDataApi",
            "com.google.android.gms.location.places.PlacesApi",
            "com.google.android.gms.location.places.PlaceDetectionApi",
            "com.google.android.gms.wearable.MESSAGE_RECEIVED",
            "com.google.android.gms.checkin.BIND_TO_SERVICE",

            // C2DM / GCM
            "com.google.android.c2dm.intent.REGISTER",
            "com.google.android.c2dm.intent.REGISTRATION",
            "com.google.android.c2dm.intent.UNREGISTER",
            "com.google.android.c2dm.intent.RECEIVE",
            "com.google.iid.TOKEN_REQUEST",
            "com.google.android.gcm.intent.SEND",

            // car
            "com.google.android.gms.car.service.START",

            // people
            "com.google.android.gms.people.service.START",

            // wearable
            "com.google.android.gms.wearable.BIND",

            // auth
            "com.google.android.gsf.login",
            "com.google.android.gsf.action.GET_GLS",
            "com.google.android.gms.common.account.CHOOSE_ACCOUNT",
            "com.google.android.gms.auth.login.LOGIN",
            "com.google.android.gms.auth.api.credentials.PICKER",
            "com.google.android.gms.auth.api.credentials.service.START",
            "com.google.android.gms.auth.service.START",
            "com.google.firebase.auth.api.gms.service.START",
            "com.google.android.gms.auth.be.appcert.AppCertService",
            "com.google.android.gms.credential.manager.service.firstparty.START",
            "com.google.android.gms.auth.GOOGLE_SIGN_IN",
            "com.google.android.gms.signin.service.START",
            "com.google.android.gms.auth.api.signin.service.START",
            "com.google.android.gms.auth.api.identity.service.signin.START",
            "com.google.android.gms.accountsettings.action.VIEW_SETTINGS",

            // fido
            "com.google.android.gms.fido.fido2.privileged.START",

            // gass
            "com.google.android.gms.gass.START",

            // games
            "com.google.android.gms.games.service.START",
            "com.google.android.gms.games.PLAY_GAMES_UPGRADE",
            "com.google.android.gms.games.internal.connect.service.START",

            // help
            "com.google.android.gms.googlehelp.service.GoogleHelpService.START",
            "com.google.android.gms.googlehelp.HELP",
            "com.google.android.gms.feedback.internal.IFeedbackService",

            // cast
            "com.google.android.gms.cast.service.BIND_CAST_DEVICE_CONTROLLER_SERVICE",

            // chimera
            "com.google.android.gms.chimera",

            // fonts
            "com.google.android.gms.fonts",

            // phenotype
            "com.google.android.gms.phenotype.service.START",

            // location
            "com.google.android.gms.location.reporting.service.START",

            // misc
            "com.google.android.gms.gmscompliance.service.START",
            "com.google.android.gms.oss.licenses.service.START",
            "com.google.android.gms.tapandpay.service.BIND",
            "com.google.android.gms.measurement.START",
            "com.google.android.gms.languageprofile.service.START",
            "com.google.android.gms.clearcut.service.START",
            "com.google.android.gms.icing.LIGHTWEIGHT_INDEX_SERVICE",
            "com.google.android.gms.icing.INDEX_SERVICE",
            "com.google.android.gms.mdm.services.START",
            "com.google.android.gms.clearcut.service.START",

            // potoken
            "com.google.android.gms.potokens.service.START",

            // droidguard, safetynet
            "com.google.android.gms.droidguard.service.START",
            "com.google.android.gms.safetynet.service.START",
        )

        /**
         * All content provider authorities.
         */
        val AUTHORITIES = setOf(
            // gsf
            "com.google.android.gsf.gservices",
            "com.google.settings",

            // auth
            "com.google.android.gms.auth.accounts",

            // chimera
            "com.google.android.gms.chimera",

            // fonts
            "com.google.android.gms.fonts",

            // phenotype
            "com.google.android.gms.phenotype",
        )
    }

    // endregion
}
