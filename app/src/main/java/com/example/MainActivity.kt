package com.example

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.data.PawLedgerRepository
import com.example.data.local.AppPreferences
import com.example.data.local.PawLedgerDatabase
import com.example.data.local.UserPreferencesStore
import com.example.data.remote.AuthManager
import com.example.data.remote.FirestoreSyncRepository
import com.example.security.BiometricHelper
import com.example.security.EncryptionManager
import com.example.ui.PawLedgerViewModel
import com.example.ui.navigation.AdminSettingsRoute
import com.example.ui.navigation.AnalyticsRoute
import com.example.ui.navigation.ContractDetailRoute
import com.example.ui.navigation.ContractsRoute
import com.example.ui.navigation.CreateContractRoute
import com.example.ui.navigation.DashboardRoute
import com.example.ui.navigation.EditContractRoute
import com.example.ui.navigation.PawRoute
import com.example.ui.screens.AdminSettingsScreen
import com.example.ui.screens.AnalyticsScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.BiometricLockScreen
import com.example.ui.screens.ContractDetailScreen
import com.example.ui.screens.ContractsScreen
import com.example.ui.screens.CreateContractScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.theme.PawLedgerTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class StartupServices(
    val auth: FirebaseAuth,
    val firestore: FirebaseFirestore,
    val localDb: PawLedgerDatabase
)

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefsStore = remember { UserPreferencesStore(applicationContext) }
            val prefsState by prefsStore.preferencesFlow.collectAsStateWithLifecycle(initialValue = null)
            val prefs = prefsState ?: AppPreferences()

            val services by produceState<StartupServices?>(initialValue = null) {
                value = withContext(Dispatchers.IO) {
                    val databaseId = applicationContext.getString(R.string.firestore_database_id)
                    StartupServices(
                        auth = Firebase.auth,
                        firestore = FirebaseFirestore.getInstance(databaseId),
                        localDb = PawLedgerDatabase.getInstance(applicationContext)
                    )
                }
            }

            // Apply FLAG_SECURE only once preferences load and only if flag state actually changed
            LaunchedEffect(prefsState?.biometricLockEnabled) {
                val enabled = prefsState?.biometricLockEnabled ?: return@LaunchedEffect
                val currentlySecure = (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
                if (enabled && !currentlySecure) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else if (!enabled && currentlySecure) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            PawLedgerTheme(themeMode = prefs.themeMode) {
                val readyServices = services
                if (readyServices == null) {
                    // Paint initial themed frame immediately so SurfaceSyncGroup never times out
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    )
                } else {
                    PawLedgerRoot(
                        activity = this,
                        prefsStore = prefsStore,
                        prefs = prefs,
                        services = readyServices
                    )
                }
            }
        }
    }
}

@Composable
private fun PawLedgerRoot(
    activity: FragmentActivity,
    prefsStore: UserPreferencesStore,
    prefs: AppPreferences,
    services: StartupServices
) {
    val currentUser by AuthManager.authStateFlow(services.auth).collectAsStateWithLifecycle(
        initialValue = services.auth.currentUser
    )
    val user = currentUser

    if (user == null) {
        AuthScreen(onAuthSuccess = {})
        return
    }

    var biometricUnlocked by remember(user.uid, prefs.biometricLockEnabled) {
        mutableStateOf(!prefs.biometricLockEnabled)
    }
    var bioError by remember { mutableStateOf<String?>(null) }
    val windowInfo = LocalWindowInfo.current

    fun triggerBiometric() {
        BiometricHelper.authenticate(
            activity = activity,
            title = activity.getString(R.string.biometric_title),
            subtitle = activity.getString(R.string.biometric_subtitle),
            onSuccess = {
                bioError = null
                biometricUnlocked = true
            },
            onError = { msg -> bioError = msg }
        )
    }

    LaunchedEffect(user.uid, prefs.biometricLockEnabled, windowInfo.isWindowFocused) {
        if (prefs.biometricLockEnabled && !biometricUnlocked && windowInfo.isWindowFocused) {
            withFrameNanos { }
            if (BiometricHelper.canAuthenticate(activity)) {
                triggerBiometric()
            } else {
                biometricUnlocked = true
            }
        }
    }

    if (prefs.biometricLockEnabled && !biometricUnlocked) {
        BiometricLockScreen(
            errorText = bioError,
            onUnlockClick = { triggerBiometric() }
        )
        return
    }

    val uid = user.uid
    key(uid) {
        val viewModel: PawLedgerViewModel = viewModel(
            key = uid,
            factory = viewModelFactory {
                initializer {
                    val app = checkNotNull(this[APPLICATION_KEY])
                    val encryption = EncryptionManager()
                    val syncRepo = FirestoreSyncRepository(
                        db = services.firestore,
                        localDb = services.localDb,
                        encryptionManager = encryption,
                        appContext = app,
                        auth = services.auth
                    )
                    val repo = PawLedgerRepository(services.localDb, syncRepo, app)
                    PawLedgerViewModel(
                        currentUserId = uid,
                        repository = repo,
                        syncRepository = syncRepo,
                        prefsStore = prefsStore,
                        encryptionManager = encryption
                    )
                }
            }
        )

        MainAdaptiveShell(
            viewModel = viewModel,
            userEmail = user.email
        )
    }
}

private data class TopNavTab(
    val route: PawRoute,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

@Composable
private fun MainAdaptiveShell(
    viewModel: PawLedgerViewModel,
    userEmail: String?
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val snackbarHostState = remember { SnackbarHostState() }
    val feedback by viewModel.feedbackMessage.collectAsStateWithLifecycle()

    LaunchedEffect(feedback) {
        val msg = feedback ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearFeedback()
    }

    val tabs = listOf(
        TopNavTab(DashboardRoute, "Dashboard", Icons.Filled.Dashboard, Icons.Outlined.Dashboard),
        TopNavTab(ContractsRoute, "Contracts", Icons.Filled.Description, Icons.Outlined.Description),
        TopNavTab(AnalyticsRoute, "Analytics", Icons.Filled.Analytics, Icons.Outlined.Analytics),
        TopNavTab(AdminSettingsRoute, "Admin", Icons.Filled.AdminPanelSettings, Icons.Outlined.AdminPanelSettings)
    )

    val showTopLevelChrome = destination?.hasRoute<CreateContractRoute>() != true &&
        destination?.hasRoute<EditContractRoute>() != true &&
        destination?.hasRoute<ContractDetailRoute>() != true

    fun navigateTab(route: PawRoute) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 600.dp

        Row(modifier = Modifier.fillMaxSize()) {
            if (isWide && showTopLevelChrome) {
                NavigationRail {
                    tabs.forEach { tab ->
                        val selected = destination?.hasRoute(tab.route::class) == true
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navigateTab(tab.route) },
                            icon = {
                                Icon(
                                    if (selected) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = tab.label
                                )
                            },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }

            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    if (!isWide && showTopLevelChrome) {
                        NavigationBar {
                            tabs.forEach { tab ->
                                val selected = destination?.hasRoute(tab.route::class) == true
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateTab(tab.route) },
                                    icon = {
                                        Icon(
                                            if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tab.label
                                        )
                                    },
                                    label = { Text(tab.label) }
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.TopCenter
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = DashboardRoute,
                        modifier = Modifier.widthIn(max = 840.dp)
                    ) {
                        composable<DashboardRoute> {
                            DashboardScreen(
                                viewModel = viewModel,
                                onCreateContract = { navController.navigate(CreateContractRoute) },
                                onOpenContract = { id -> navController.navigate(ContractDetailRoute(id)) }
                            )
                        }
                        composable<ContractsRoute> {
                            ContractsScreen(
                                viewModel = viewModel,
                                onCreateContract = { navController.navigate(CreateContractRoute) },
                                onOpenContract = { id -> navController.navigate(ContractDetailRoute(id)) },
                                onEditContract = { id -> navController.navigate(EditContractRoute(id)) }
                            )
                        }
                        composable<AnalyticsRoute> {
                            AnalyticsScreen(
                                viewModel = viewModel,
                                onOpenContract = { id -> navController.navigate(ContractDetailRoute(id)) }
                            )
                        }
                        composable<AdminSettingsRoute> {
                            AdminSettingsScreen(
                                viewModel = viewModel,
                                userEmail = userEmail
                            )
                        }
                        composable<CreateContractRoute> {
                            CreateContractScreen(
                                viewModel = viewModel,
                                onBack = { navController.popBackStack() },
                                onContractCreated = { newId ->
                                    navController.navigate(ContractDetailRoute(newId)) {
                                        popUpTo<CreateContractRoute> { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable<EditContractRoute> { entry ->
                            val route = entry.toRoute<EditContractRoute>()
                            CreateContractScreen(
                                viewModel = viewModel,
                                editContractId = route.contractId,
                                onBack = { navController.popBackStack() },
                                onContractCreated = { navController.popBackStack() }
                            )
                        }
                        composable<ContractDetailRoute> { entry ->
                            val route = entry.toRoute<ContractDetailRoute>()
                            ContractDetailScreen(
                                contractId = route.contractId,
                                viewModel = viewModel,
                                onBack = { navController.popBackStack() },
                                onEditContract = { navController.navigate(EditContractRoute(route.contractId)) }
                            )
                        }
                    }
                }
            }
        }
    }
}
