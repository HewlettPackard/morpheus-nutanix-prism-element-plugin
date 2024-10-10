package com.morpheusdata.nutanix.prismelement.plugin.backup

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.backup.BackupRestoreProvider
import com.morpheusdata.core.backup.response.BackupRestoreResponse
import com.morpheusdata.model.Backup
import com.morpheusdata.model.BackupRestore
import com.morpheusdata.model.BackupResult
import com.morpheusdata.model.Instance
import com.morpheusdata.response.ServiceResponse

class NutanixPrismElementBackupRestoreProvider implements BackupRestoreProvider {
	protected Plugin plugin
	protected MorpheusContext context

	NutanixPrismElementBackupRestoreProvider(Plugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.context = context
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse configureRestoreBackup(BackupResult backupResultModel, Map config, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse getBackupRestoreInstanceConfig(BackupResult backupResultModel, Instance instanceModel, Map restoreConfig, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse validateRestoreBackup(BackupResult backupResultModel, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse getRestoreOptions(Backup backupModel, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse<BackupRestoreResponse> restoreBackup(BackupRestore backupRestoreModel, BackupResult backupResultModel, Backup backupModel, Map opts) {
		return null // TODO
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse<BackupRestoreResponse> refreshBackupRestoreResult(BackupRestore backupRestore, BackupResult backupResult) {
		return ServiceResponse.success()
	}
}
