/*
 * Copyright 2024 Morpheus Data, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

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
		return ServiceResponse.success(config)
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse getBackupRestoreInstanceConfig(BackupResult backupResultModel, Instance instanceModel, Map restoreConfig, Map opts) {
		return ServiceResponse.success(restoreConfig)
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
		return ServiceResponse.success(new BackupRestoreResponse(backupRestoreModel))
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse<BackupRestoreResponse> refreshBackupRestoreResult(BackupRestore backupRestore, BackupResult backupResult) {
		return ServiceResponse.success()
	}
}
