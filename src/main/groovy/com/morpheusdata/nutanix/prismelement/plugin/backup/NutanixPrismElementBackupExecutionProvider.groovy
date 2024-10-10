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
import com.morpheusdata.core.backup.BackupExecutionProvider
import com.morpheusdata.core.backup.response.BackupExecutionResponse
import com.morpheusdata.model.Backup
import com.morpheusdata.model.BackupResult
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.response.ServiceResponse

class NutanixPrismElementBackupExecutionProvider implements BackupExecutionProvider {
	protected Plugin plugin
	protected MorpheusContext context

	NutanixPrismElementBackupExecutionProvider(Plugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.context = context
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse configureBackup(Backup backupModel, Map config, Map opts) {
		ServiceResponse.success()
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse validateBackup(Backup backupModel, Map config, Map opts) {
		ServiceResponse.success()
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse createBackup(Backup backupModel, Map opts) {
		ServiceResponse.success()
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse deleteBackup(Backup backupModel, Map opts) {
		ServiceResponse.success()
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse deleteBackupResult(BackupResult backupResultModel, Map opts) {
		ServiceResponse.success() // TODO
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse prepareExecuteBackup(Backup backupModel, Map opts) {
		ServiceResponse.success()
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse prepareBackupResult(BackupResult backupResultModel, Map opts) {
		ServiceResponse.success()
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse<BackupExecutionResponse> executeBackup(Backup backup, BackupResult backupResult, Map executionConfig, Cloud cloud, ComputeServer computeServer, Map opts) {
		ServiceResponse.success(new BackupExecutionResponse(backupResult)) // TODO
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse<BackupExecutionResponse> refreshBackupResult(BackupResult backupResult) {
		ServiceResponse.success(new BackupExecutionResponse(backupResult)) // TODO
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse cancelBackup(BackupResult backupResultModel, Map opts) {
		ServiceResponse.success()
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse extractBackup(BackupResult backupResultModel, Map opts) {
		ServiceResponse.success()
	}
}
