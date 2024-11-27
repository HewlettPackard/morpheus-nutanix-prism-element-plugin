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
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.Backup
import com.morpheusdata.model.BackupResult
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.PlatformType
import com.morpheusdata.nutanix.prismelement.plugin.utils.NutanixPrismElementApiService
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class NutanixPrismElementBackupExecutionProvider implements BackupExecutionProvider {
	protected Plugin plugin
	protected MorpheusContext morpheusContext

	NutanixPrismElementBackupExecutionProvider(Plugin plugin, MorpheusContext morpheusContext) {
		this.plugin = plugin
		this.morpheusContext = morpheusContext
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
	ServiceResponse deleteBackupResult(BackupResult backupResult, Map opts) {
		ServiceResponse rtn = ServiceResponse.prepare()
		HttpApiClient client = new HttpApiClient()

		try {
			def snapshotId = backupResult.getConfigProperty("snapshotId")
			if (!snapshotId) {
				rtn.success = false
				rtn.msg = "Associate snapshotId could not be found."
				return rtn
			}

			def cloudId = backupResult.zoneId ?: backupResult.backup?.zoneId
			if (!cloudId) {
				rtn.success = false
				rtn.msg = "Associated cloud could not be found."
				return rtn
			}

			def cloud = morpheusContext.services.cloud.get(cloudId)
			client.networkProxy = cloud.apiProxy

			def result = NutanixPrismElementApiService.deleteSnapshot(client, [zone: cloud], snapshotId)
			rtn.success = result.success
		} catch(e) {
			log.error("error deleting backup: ${e.message}", e)
			rtn.success = false
		} finally {
			client.shutdownClient()
		}

		return rtn
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
	ServiceResponse<BackupExecutionResponse> executeBackup(
		Backup backup,
		BackupResult backupResult,
		Map executionConfig,
		Cloud cloud,
		ComputeServer server,
		Map opts
	) {
		log.debug("Executing backup {} with result {}", backup.id, backupResult.id)
		ServiceResponse<BackupExecutionResponse> rtn = ServiceResponse.prepare(new BackupExecutionResponse(backupResult))

		HttpApiClient client = new HttpApiClient()
		client.networkProxy = cloud.apiProxy

		try {
			// Clean out vm unique markers before taking a snapshot
			if(server.serverOs?.platform != PlatformType.windows) {
				morpheusContext.executeCommandOnServer(server,
					'sudo rm -f /etc/cloud/cloud.cfg.d/99-manual-cache.cfg; sudo cp /etc/machine-id /tmp/machine-id-old; sudo rm -f /etc/machine-id; sudo touch /etc/machine-id ; sync ; sync ; sleep 5',
					false, server.sshUsername, server.sshPassword, null, null,
					null, null, true, true).blockingGet()
			}

			def workload = morpheusContext.services.workload.get(backup.containerId)
			def snapshotName = "${workload.instance.name}.${workload.id}.${System.currentTimeMillis()}".toString()
			def snapshotOpts = [
				zone: cloud,
				vmId: server.externalId,
				snapshotName: snapshotName
			]
			def snapshotResults = NutanixPrismElementApiService.createSnapshot(client, snapshotOpts)
			def taskId = snapshotResults?.results?.taskUuid
			if (snapshotResults.success && taskId) {
				rtn.success = true
				// set config properties for legacy embedded compatibility.
				rtn.data.backupResult.setConfigProperty("taskId", taskId)
				rtn.data.backupResult.setConfigProperty("snapshotName", snapshotName)
				rtn.data.backupResult.internalId = taskId
				rtn.data.backupResult.backupName = snapshotName
				rtn.data.backupResult.sizeInMb = 0
				rtn.data.updates = true
			} else {
				//error
				rtn.data.backupResult.sizeInMb = 0
				rtn.data.backupResult.errorOutput = snapshotResults?.msg
				rtn.data.updates = true
			}
		} catch (e) {
			log.error("executeBackup: ${e}", e)
			rtn.error = e.getMessage()
		}finally {
			client.shutdownClient()
		}

		return rtn
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	ServiceResponse<BackupExecutionResponse> refreshBackupResult(BackupResult backupResult) {
		log.debug("Refreshing backup with result {}", backupResult)
		ServiceResponse<BackupExecutionResponse> rtn  = ServiceResponse.prepare(new BackupExecutionResponse(backupResult))

		HttpApiClient client = new HttpApiClient()

		try {
			def cloudId = backupResult.zoneId ?: backupResult.backup?.zoneId
			if(!cloudId) {
				rtn.data.backupResult.status = BackupResult.Status.FAILED
				rtn.data.msg = "Associated cloud not found"
				rtn.success = false
				rtn.data.updates = true
				return rtn
			}

			def serverId = backupResult.serverId ?: backupResult.backup?.computeServerId
			if(!serverId) {
				rtn.data.backupResult.status = BackupResult.Status.FAILED
				rtn.data.msg = "Associated server not found"
				rtn.success = false
				rtn.data.updates = true
				return rtn
			}

			def cloud = morpheusContext.services.cloud.get(cloudId)

			client.networkProxy = cloud.apiProxy
			def server = morpheusContext.services.computeServer.get(serverId)

			def taskResults = NutanixPrismElementApiService.getTask(client, [zone: cloud], backupResult.getConfigMap().taskId)
			if(taskResults.success == true && taskResults.results.percentage_complete == 100) {
				def results = taskResults.results
				if(results.progress_status == 'Succeeded') {
					rtn.success = true
					rtn.data.backupResult.status = BackupResult.Status.SUCCEEDED
				} else if(results.progress_status == 'Failure' || results.progress_status == 'Failed') {
					rtn.data.backupResult.status = BackupResult.Status.FAILED
				}
				rtn.data.backupResult.endDate = results.completeTime

				//get snapshot ID
				def entities = results.entity_list
				entities.each{ entity ->
					if(entity.entity_type?.equals("Snapshot")) {
						rtn.data.backupResult.setConfigProperty("snapshotId", entity.entity_id)
					}
				}

				def startTime = results.start_time_usecs
				def endTime = results.complete_time_usecs
				if(startTime && endTime) {
					rtn.data.backupResult.durationMillis = (endTime - startTime) / 1000
				}

				rtn.data.updates = true
			}

			if([BackupResult.Status.FAILED, BackupResult.Status.CANCELLED, BackupResult.Status.SUCCEEDED].contains(rtn.data.backupResult.status)) {
				if(server.sourceImage?.isCloudInit && server.serverOs?.platform != PlatformType.windows) {
					// Since we cleared out the cloud init + machine-id for the snapshot, put it back in place
					morpheusContext.executeCommandOnServer(server,
						"sudo bash -c \"echo 'manual_cache_clean: True' >> /etc/cloud/cloud.cfg.d/99-manual-cache.cfg\"; sudo cat /tmp/machine-id-old > /etc/machine-id ; sudo rm /tmp/machine-id-old ; sync",
						false, server.sshUsername, server.sshPassword, null, null,
						null, null, true, true).blockingGet()
				}
			}
		} finally {
			client.shutdownClient()
		}

		return rtn
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
