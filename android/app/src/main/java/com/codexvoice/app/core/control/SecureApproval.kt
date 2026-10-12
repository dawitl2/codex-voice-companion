package com.codexvoice.app.core.control

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.atomic.AtomicBoolean

/** No authentication grace period, saved authorization flag, or voice-accessible signing path. */
object SecureApproval {
    private val mutableDiagnostic=kotlinx.coroutines.flow.MutableStateFlow("idle")
    val diagnostic: kotlinx.coroutines.flow.StateFlow<String> = mutableDiagnostic
    private const val ALIAS="codex-voice-review-per-use-v1"
    private fun keyStore()=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
    fun publicKey():String {
        val store=keyStore()
        if(!store.containsAlias(ALIAS))KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore").apply{
            initialize(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true).setUserAuthenticationParameters(0,KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL).build())
            generateKeyPair()
        }
        return Base64.encodeToString(keyStore().getCertificate(ALIAS).publicKey.encoded,Base64.NO_WRAP)
    }
    fun authenticate(activity:Activity,challenge:ApprovalChallenge,onResult:(String?)->Unit):CancellationSignal {
        mutableDiagnostic.value="preparing"
        val cancellation=CancellationSignal();val done=AtomicBoolean(false);val handler=Handler(Looper.getMainLooper())
        fun finish(signature:String?){if(done.compareAndSet(false,true))onResult(signature)}
        val remaining=challenge.expiresAt-System.currentTimeMillis()
        if(remaining<=0){mutableDiagnostic.value="challenge_expired";finish(null);return cancellation}
        try {
            val flags=BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            val availability=activity.getSystemService(BiometricManager::class.java).canAuthenticate(flags)
            if(availability!=BiometricManager.BIOMETRIC_SUCCESS){mutableDiagnostic.value="authentication_unavailable_$availability";finish(null);return cancellation}
            val privateKey=keyStore().getKey(ALIAS,null) as java.security.PrivateKey
            val signing=Signature.getInstance("SHA256withECDSA").apply{initSign(privateKey)}
            val prompt=BiometricPrompt.Builder(activity).setTitle("Approve this action once")
                .setSubtitle("Codex Voice · fresh authorization").setAllowedAuthenticators(flags).setConfirmationRequired(true).build()
            mutableDiagnostic.value="prompt_open"
            prompt.authenticate(BiometricPrompt.CryptoObject(signing),cancellation,activity.mainExecutor,object:BiometricPrompt.AuthenticationCallback(){
                override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult){
                    try{check(System.currentTimeMillis()<challenge.expiresAt);val signature=checkNotNull(result.cryptoObject?.signature);signature.update(challenge.message.toByteArray(Charsets.UTF_8));mutableDiagnostic.value="signed";finish(Base64.encodeToString(signature.sign(),Base64.NO_WRAP))}catch(_:Exception){mutableDiagnostic.value="signature_unavailable";finish(null)}
                }
                override fun onAuthenticationError(errorCode:Int,errString:CharSequence){mutableDiagnostic.value="authentication_error_$errorCode";finish(null)}
            })
            handler.postDelayed({if(!done.get()){cancellation.cancel();finish(null)}},remaining.coerceAtMost(30000))
        }catch(error:Exception){mutableDiagnostic.value="crypto_unavailable_"+error.javaClass.simpleName;finish(null)}
        return cancellation
    }
}
