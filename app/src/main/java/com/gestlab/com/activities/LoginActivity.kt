package com.gestlab.com.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.gestlab.com.CredentialStore
import com.gestlab.com.R
import com.gestlab.com.VpnCredentials
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText

/** Saisie de l'identifiant et du mot de passe VPN (auth-user-pass). */
class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val store = CredentialStore(this)
        val user = findViewById<TextInputEditText>(R.id.login_user)
        val pass = findViewById<TextInputEditText>(R.id.login_password)
        val remember = findViewById<MaterialCheckBox>(R.id.login_remember)
        val error = findViewById<TextView>(R.id.login_error)
        val submit = findViewById<MaterialButton>(R.id.login_submit)

        user.setText(store.lastUsername())
        if (!user.text.isNullOrEmpty()) pass.requestFocus()

        intent.getStringExtra(EXTRA_ERROR)?.let {
            error.text = it
            error.visibility = View.VISIBLE
        }

        fun submit() {
            val u = user.text?.toString()?.trim().orEmpty()
            val p = pass.text?.toString().orEmpty()
            if (u.isEmpty() || p.isEmpty()) {
                error.setText(R.string.login_missing)
                error.visibility = View.VISIBLE
                return
            }
            val credentials = VpnCredentials(u, p)
            if (remember.isChecked) store.save(credentials) else store.clear()
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(EXTRA_USER, u).putExtra(EXTRA_PASSWORD, p)
            )
            finish()
        }

        submit.setOnClickListener { submit() }
        pass.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit(); true
            } else false
        }
    }

    companion object {
        const val EXTRA_USER = "user"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_ERROR = "error"

        fun intent(context: Context, error: String? = null): Intent =
            Intent(context, LoginActivity::class.java).apply {
                if (error != null) putExtra(EXTRA_ERROR, error)
            }

        fun credentialsFrom(data: Intent?): VpnCredentials? {
            val u = data?.getStringExtra(EXTRA_USER) ?: return null
            val p = data.getStringExtra(EXTRA_PASSWORD) ?: return null
            return VpnCredentials(u, p)
        }
    }
}
