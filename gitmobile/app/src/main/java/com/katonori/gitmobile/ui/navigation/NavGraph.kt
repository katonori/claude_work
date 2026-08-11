package com.katonori.gitmobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.katonori.gitmobile.di.AppContainer
import com.katonori.gitmobile.ui.branch.BranchListScreen
import com.katonori.gitmobile.ui.branch.BranchListViewModel
import com.katonori.gitmobile.ui.clone.CloneScreen
import com.katonori.gitmobile.ui.clone.CloneViewModel
import com.katonori.gitmobile.ui.commit.CommitScreen
import com.katonori.gitmobile.ui.commit.CommitViewModel
import com.katonori.gitmobile.ui.common.vmFactory
import com.katonori.gitmobile.ui.dashboard.RepoDashboardScreen
import com.katonori.gitmobile.ui.dashboard.RepoDashboardViewModel
import com.katonori.gitmobile.ui.diff.DiffViewerScreen
import com.katonori.gitmobile.ui.diff.DiffViewerViewModel
import com.katonori.gitmobile.ui.history.CommitDetailScreen
import com.katonori.gitmobile.ui.history.CommitDetailViewModel
import com.katonori.gitmobile.ui.history.HistoryScreen
import com.katonori.gitmobile.ui.history.HistoryViewModel
import com.katonori.gitmobile.ui.repolist.RepoListScreen
import com.katonori.gitmobile.ui.repolist.RepoListViewModel
import com.katonori.gitmobile.ui.settings.SettingsScreen
import com.katonori.gitmobile.ui.settings.SettingsViewModel

@Composable
fun GitMobileNavGraph(container: AppContainer, navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.REPO_LIST, modifier = Modifier) {
        composable(Routes.REPO_LIST) {
            val vm: RepoListViewModel = viewModel(
                factory = vmFactory { RepoListViewModel(container.localRepoStore, container.gitRepositoryManager) }
            )
            RepoListScreen(
                viewModel = vm,
                onAddRepo = { navController.navigate(Routes.CLONE) },
                onOpenRepo = { repoId -> navController.navigate(Routes.dashboard(repoId)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.CLONE) {
            val vm: CloneViewModel = viewModel(
                factory = vmFactory {
                    CloneViewModel(container.gitRepositoryManager, container.localRepoStore, container.credentialStore)
                }
            )
            CloneScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onCloned = { repoId ->
                    navController.popBackStack()
                    navController.navigate(Routes.dashboard(repoId))
                },
            )
        }

        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel(
                factory = vmFactory { SettingsViewModel(container.userPreferencesStore, container.credentialStore) }
            )
            SettingsScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }

        composable(
            Routes.DASHBOARD,
            arguments = listOf(navArgument("repoId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val repoId = backStackEntry.arguments?.getString("repoId").orEmpty()
            val vm: RepoDashboardViewModel = viewModel(
                factory = vmFactory {
                    RepoDashboardViewModel(repoId, container.gitRepositoryManager, container.localRepoStore, container.credentialStore)
                }
            )
            RepoDashboardScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenCommit = { navController.navigate(Routes.commit(repoId)) },
                onOpenBranches = { navController.navigate(Routes.branches(repoId)) },
                onOpenHistory = { navController.navigate(Routes.history(repoId)) },
                onOpenWorkingDiff = { navController.navigate(Routes.diffWorking(repoId)) },
            )
        }

        composable(
            Routes.COMMIT,
            arguments = listOf(navArgument("repoId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val repoId = backStackEntry.arguments?.getString("repoId").orEmpty()
            val vm: CommitViewModel = viewModel(
                factory = vmFactory {
                    CommitViewModel(repoId, container.gitRepositoryManager, container.userPreferencesStore)
                }
            )
            CommitScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onCommitted = { navController.popBackStack() },
            )
        }

        composable(
            Routes.BRANCHES,
            arguments = listOf(navArgument("repoId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val repoId = backStackEntry.arguments?.getString("repoId").orEmpty()
            val vm: BranchListViewModel = viewModel(
                factory = vmFactory { BranchListViewModel(repoId, container.gitRepositoryManager) }
            )
            BranchListScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }

        composable(
            Routes.HISTORY,
            arguments = listOf(navArgument("repoId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val repoId = backStackEntry.arguments?.getString("repoId").orEmpty()
            val vm: HistoryViewModel = viewModel(
                factory = vmFactory { HistoryViewModel(repoId, container.gitRepositoryManager) }
            )
            HistoryScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenCommit = { commitId -> navController.navigate(Routes.commitDetail(repoId, commitId)) },
            )
        }

        composable(
            Routes.COMMIT_DETAIL,
            arguments = listOf(
                navArgument("repoId") { type = NavType.StringType },
                navArgument("commitId") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val repoId = backStackEntry.arguments?.getString("repoId").orEmpty()
            val commitId = backStackEntry.arguments?.getString("commitId").orEmpty()
            val vm: CommitDetailViewModel = viewModel(
                factory = vmFactory { CommitDetailViewModel(repoId, commitId, container.gitRepositoryManager) }
            )
            CommitDetailScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }

        composable(
            Routes.DIFF_WORKING,
            arguments = listOf(navArgument("repoId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val repoId = backStackEntry.arguments?.getString("repoId").orEmpty()
            val vm: DiffViewerViewModel = viewModel(
                factory = vmFactory { DiffViewerViewModel(repoId, container.gitRepositoryManager) }
            )
            DiffViewerScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
    }
}
