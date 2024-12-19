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
import com.morpheusdata.core.backup.AbstractBackupTypeProvider
import com.morpheusdata.core.backup.BackupExecutionProvider
import com.morpheusdata.core.backup.BackupRestoreProvider
import com.morpheusdata.model.BackupProvider
import com.morpheusdata.model.OptionType
import com.morpheusdata.response.ServiceResponse

class NutanixPrismElementBackupTypeProvider extends AbstractBackupTypeProvider {
	public static final String PROVIDER_CODE = 'nutanixSnapshot'
	public static final String PROVIDER_NAME = 'Nutanix VM Snapshot'

	protected NutanixPrismElementBackupExecutionProvider executionProvider
	protected NutanixPrismElementBackupRestoreProvider restoreProvider


	NutanixPrismElementBackupTypeProvider(Plugin plugin, MorpheusContext morpheusContext) {
		super(plugin, morpheusContext)
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	String getCode() {
		PROVIDER_CODE
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	String getName() {
		PROVIDER_NAME
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	String getContainerType() {
		'single'
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Boolean getCopyToStore() {
		false
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Boolean getDownloadEnabled() {
		false
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Boolean getRestoreExistingEnabled() {
		false
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Boolean getRestoreNewEnabled() {
		true
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	String getRestoreType() {
		'offline'
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	String getRestoreNewMode() {
		null
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Boolean getHasCopyToStore() {
		false
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	Collection<OptionType> getOptionTypes() {
		[]
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	BackupExecutionProvider getExecutionProvider() {
		if (executionProvider == null) {
			executionProvider = new NutanixPrismElementBackupExecutionProvider(plugin, morpheus)
		}
		return executionProvider
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	BackupRestoreProvider getRestoreProvider() {
		if (restoreProvider == null) {
			restoreProvider = new NutanixPrismElementBackupRestoreProvider(plugin, morpheus)
		}
		return restoreProvider
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse refresh(Map reqConfig, BackupProvider backupProvider) {
		return ServiceResponse.success()
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse clean(BackupProvider backupProvider, Map opts) {
		return ServiceResponse.success()
	}
}
