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


	NutanixPrismElementBackupTypeProvider(Plugin plugin, MorpheusContext context) {
		super(plugin, context)
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
	ServiceResponse refresh(Map authConfig, BackupProvider backupProvider) {
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
