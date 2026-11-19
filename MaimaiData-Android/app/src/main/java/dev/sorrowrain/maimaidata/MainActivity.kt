package dev.sorrowrain.maimaidata

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import dev.sorrowrain.maimaidata.services.DataStore
import dev.sorrowrain.maimaidata.services.LocalDataStore
import dev.sorrowrain.maimaidata.theme.MaimaiDataTheme
import dev.sorrowrain.maimaidata.views.MainTabView

class MainActivity : ComponentActivity() {
    private val dataStore: DataStore
        get() = (application as MaimaiDataApplication).dataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            dispatchOAuthCallbackIfPresent(intent)
        }

        setContent {
            MaimaiDataTheme {
                CompositionLocalProvider(LocalDataStore provides dataStore) {
                    MainTabView()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchOAuthCallbackIfPresent(intent)
    }

    private fun dispatchOAuthCallbackIfPresent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "maimaidata") {
            intent.data = null
            dataStore.onOAuthCallbackIntent(uri.toString())
        }
    }
}
