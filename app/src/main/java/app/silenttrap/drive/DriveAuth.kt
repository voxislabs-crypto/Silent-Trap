package app.silenttrap.drive

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope

object DriveAuth {
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val FOLDER_NAME = "Silent Trap Segments"

    fun options(): GoogleSignInOptions {
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(SCOPE))
            .build()
    }

    fun client(context: Context): GoogleSignInClient {
        return GoogleSignIn.getClient(context.applicationContext, options())
    }

    fun signInIntent(context: Context): Intent = client(context).signInIntent

    fun account(context: Context): GoogleSignInAccount? {
        val account = GoogleSignIn.getLastSignedInAccount(context.applicationContext) ?: return null
        return if (GoogleSignIn.hasPermissions(account, Scope(SCOPE))) account else null
    }

    fun email(context: Context): String? = account(context)?.email
}
