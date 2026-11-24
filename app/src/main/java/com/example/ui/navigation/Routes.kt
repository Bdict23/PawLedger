package com.example.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed interface PawRoute

@Serializable
data object DashboardRoute : PawRoute

@Serializable
data object ContractsRoute : PawRoute

@Serializable
data object AnalyticsRoute : PawRoute

@Serializable
data object AdminSettingsRoute : PawRoute

@Serializable
data object CreateContractRoute : PawRoute

@Serializable
data class ContractDetailRoute(val contractId: String) : PawRoute

@Serializable
data class EditContractRoute(val contractId: String) : PawRoute
