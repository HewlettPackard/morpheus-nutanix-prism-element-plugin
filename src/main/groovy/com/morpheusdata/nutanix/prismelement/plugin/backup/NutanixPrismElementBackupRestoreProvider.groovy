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
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.Backup
import com.morpheusdata.model.BackupRestore
import com.morpheusdata.model.BackupResult
import com.morpheusdata.model.Instance
import com.morpheusdata.nutanix.prismelement.plugin.utils.NutanixPrismElementApiService
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class NutanixPrismElementBackupRestoreProvider implements BackupRestoreProvider {
	protected Plugin plugin
	protected MorpheusContext morpheusContext

	NutanixPrismElementBackupRestoreProvider(Plugin plugin, MorpheusContext morpheusContext) {
		this.plugin = plugin
		this.morpheusContext = morpheusContext
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
	ServiceResponse<BackupRestoreResponse> restoreBackup(BackupRestore backupRestore, BackupResult backupResult, Backup backup, Map opts) {
		log.debug("Restoring backup with result {}", backupResult)
		ServiceResponse<BackupRestoreResponse> rtn = new ServiceResponse<>(false, null, null, new BackupRestoreResponse(backupRestore))

		HttpApiClient client = new HttpApiClient()

		try {
			def cloudId = backupResult.zoneId ?: backupResult.backup?.zoneId
			if(!cloudId) {
				rtn.data.backupRestore.status = BackupResult.Status.FAILED
				rtn.data.backupRestore.errorMessage = "Associated cloud not found"
				rtn.success = false
				rtn.data.updates = true
				return rtn
			}

			def serverId = backupResult.serverId ?: backupResult.backup?.computeServerId
			if(!serverId) {
				rtn.data.backupRestore.status = BackupResult.Status.FAILED
				rtn.data.backupRestore.errorMessage = "Associated server not found"
				rtn.success = false
				rtn.data.updates = true
				return rtn
			}

			def cloud = morpheusContext.services.cloud.get(cloudId)

			// now that we've got our cloud, set the proxy
			client.networkProxy = cloud.apiProxy

			def server = morpheusContext.services.computeServer.get(serverId)

			def reqConfig = NutanixPrismElementApiService.getRequestConfig(morpheusContext, cloud)
			def resp = NutanixPrismElementApiService.restoreSnapshot(client, reqConfig, [vmId: server.externalId, snapshotId:backupRestore.externalId])
			if (!resp.success) {
				rtn.data.backupRestore.status = BackupResult.Status.FAILED
				rtn.data.backupRestore.errorMessage = resp?.msg
				rtn.success = false
				rtn.data.updates = true
			}

			rtn.data.backupRestore.externalId = resp.results?.taskUuid
		} finally {
			client.shutdownClient()
		}

		return rtn
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse<BackupRestoreResponse> refreshBackupRestoreResult(BackupRestore backupRestore, BackupResult backupResult) {
		log.debug("Refreshing backup restore with result {}", backupResult)
		ServiceResponse<BackupRestoreResponse> rtn = new ServiceResponse<>(false, null, null, new BackupRestoreResponse(backupRestore))

		HttpApiClient client = new HttpApiClient()

		try {
			def cloudId = backupResult.zoneId ?: backupResult.backup?.zoneId
			if(!cloudId) {
				rtn.data.backupRestore.status = BackupResult.Status.FAILED
				rtn.data.backupRestore.errorMessage = "Associated cloud not found"
				rtn.success = false
				rtn.data.updates = true
				return rtn
			}

			def serverId = backupResult.serverId ?: backupResult.backup?.computeServerId
			if(!serverId) {
				rtn.data.backupRestore.status = BackupResult.Status.FAILED
				rtn.data.backupRestore.errorMessage = "Associated server not found"
				rtn.success = false
				rtn.data.updates = true
				return rtn
			}

			def cloud = morpheusContext.services.cloud.get(cloudId)

			// now that we've got our cloud, set the proxy
			client.networkProxy = cloud.apiProxy

			def reqConfig = NutanixPrismElementApiService.getRequestConfig(morpheusContext, cloud)
			def taskResults = NutanixPrismElementApiService.getTask(client, reqConfig, backupRestore.externalId)
			if(taskResults.success == true && taskResults.results.percentage_complete == 100) {
				def results = taskResults.results
				if(results.progress_status == 'Succeeded') {
					rtn.success = true
					rtn.data.backupRestore.status = BackupResult.Status.SUCCEEDED
				} else if(results.progress_status == 'Failure' || results.progress_status == 'Failed') {
					rtn.data.backupRestore.status = BackupResult.Status.FAILED
				}
				rtn.data.backupRestore.endDate = results.completeTime


				def startTime = results.start_time_usecs
				def endTime = results.complete_time_usecs
				if(startTime && endTime) {
					rtn.data.backupRestore.duration = (endTime - startTime) / 1000
				}

				rtn.data.updates = true
			}
		} finally {
			client.shutdownClient()
		}

		return rtn
	}
}
