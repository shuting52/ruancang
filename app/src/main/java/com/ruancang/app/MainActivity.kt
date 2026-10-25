package com.ruancang.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.ruancang.app.data.SettingsStore
import com.ruancang.app.data.ThemeViewModel
import com.ruancang.app.ui.WarehouseScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = SettingsStore(applicationContext)
        setContent {
            val themeVm = remember { ThemeViewModel() }
            // 首次启动：把上次保存的自定义代码应用回来
            LaunchedEffect(settings.customCode) { themeVm.applyCode(settings.customCode) }
            val theme by themeVm.colors.collectAsState()
            MaterialTheme(
                colorScheme = MaterialTheme.colorScheme.copy(
                    primary = theme.blue,
                    surface = theme.bg,
                    background = theme.bg,
                    onSurface = theme.textDark
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = theme.bg,
                    contentColor = theme.textDark
                ) {
                    WarehouseScreen(
                        theme = theme,
                        settings = settings,
                        themeVm = themeVm
                    )
                }
            }
        }
    }
}
