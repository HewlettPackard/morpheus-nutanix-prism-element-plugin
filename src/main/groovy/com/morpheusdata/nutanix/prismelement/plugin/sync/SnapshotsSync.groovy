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

package com.morpheusdata.nutanix.prismelement.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncList
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Snapshot
import com.morpheusdata.model.projection.SnapshotIdentityProjection
import com.morpheusdata.nutanix.prismelement.plugin.NutanixPrismElementPlugin
import com.morpheusdata.nutanix.prismelement.plugin.utils.NutanixPrismElementApiService
import groovy.util.logging.Slf4j

/**
 * Syncs snapshots from nutanix to their morpheus equivalent
 */
@Slf4j
class SnapshotsSync {
	private final MorpheusContext context
	private final HttpApiClient client
	private final Cloud cloud

	SnapshotsSync(MorpheusContext context, Cloud cloud, HttpApiClient client) {
		this.context = context
		this.cloud = cloud
		this.client = client
	}

	void execute() {
		log.info("Executing Snapshot sync for cloud ${cloud.name}")
		try {
			def authConfig = NutanixPrismElementPlugin.getAuthConfig(context, cloud)
			def listConfig = [:]
			def listResults = NutanixPrismElementApiService.listSnapshotsV2(client, authConfig, listConfig)
			if (listResults.success == true) {
				def objList = listResults.snapshots
				def existingItems = context.async.snapshot.list(new DataQuery().withFilter('cloud.id', cloud.id))

				SyncTask<SnapshotIdentityProjection, Map, Snapshot> syncTask = new SyncTask<>(existingItems, objList)
				syncTask.addMatchFunction { SnapshotIdentityProjection existingItem, Map cloudItem ->
					existingItem.externalId == cloudItem?.uuid
				}.withLoadObjectDetailsFromFinder { updateItems ->
					context.async.snapshot.listById(updateItems.collect { it.existingItem.id } as List<Long>)
				}.onAdd { List<Map> addItems ->
					log.debug("Adding ${addItems.size()} snapshots")
					addMissingSnapshots(addItems)
				}.onUpdate { List<SyncList.UpdateItem<Snapshot, Map>> updateItems ->
					log.debug("Updating ${updateItems.size()} snapshots")
					updateMatchedSnapshots(updateItems)
				}.onDelete { List<SnapshotIdentityProjection> removeItems ->
					log.debug("Removing ${removeItems.size()} snapshots")
					context.async.snapshot.bulkRemove(removeItems).blockingGet()
				}.start()
			}

		} catch (e) {
			log.error("cacheVirtualMachines error: ${e}", e)
		}
	}

	private void addMissingSnapshots(List<Map> addList) {
		def vmIds = addList?.findAll { it.vm_uuid }?.collect { it.vm_uuid }
		List<ComputeServer> servers = []
		if (vmIds) {
			servers = context.services.computeServer.list(
				new DataQuery()
					.withFilter('cloud.id', cloud.id)
					.withFilter('externalId', 'in', vmIds)
			)
		}

		def serversByExternalId = servers.groupBy { it.externalId }
		Map<String, ComputeServer> snapIdToServer = addList.collectEntries { cloudSnap ->
			[(cloudSnap.uuid): serversByExternalId[cloudSnap.vm_uuid]?.first()]
		}
		def snapshots = addList.collect { cloudItem ->
			def snapshotRecord = new Snapshot(
				account: cloud.account,
				cloud: cloud,
				name: cloudItem.snapshot_name,
				externalId: cloudItem.uuid,
				snapshotCreated: new Date((cloudItem.created_time / 1000).toLong())
			)

			def server = snapIdToServer[snapshotRecord.externalId]
			if (server) {
				snapshotRecord.server = server
				snapshotRecord.account = server.account
			}

			snapshotRecord
		}

		context.services.snapshot.bulkCreate(snapshots)
	}

	private void updateMatchedSnapshots(List<SyncList.UpdateItem<Snapshot, Map>> updateList) {
		def vmIds = updateList?.collect { it.masterItem.vm_uuid }
		List<ComputeServer> servers = []
		if (vmIds) {
			servers = context.services.computeServer.list(
				new DataQuery()
					.withFilter('cloud.id', cloud.id)
					.withFilter('externalId', 'in', vmIds)
					.withJoins('snapshots')
			)
		}

		def snapshotsToUpdate = []
		for (final def updateItem in updateList) {

			Snapshot snapshot = updateItem.existingItem
			def vmUuid = updateItem.masterItem.vm_uuid

			def save = false

			// Need to make sure the snapshot is associated to each volume for the server it is attached to
			if (vmUuid) {
				ComputeServer server = servers?.find { it.externalId == vmUuid }
				if (server && server.snapshots.contains(snapshot)) {
					if (snapshot.account?.id != server.account?.id) {
						snapshot.account = server.account
						save = true
					}
				}
			}

			if (save) {
				snapshotsToUpdate << snapshot
			}
		}

		context.services.snapshot.bulkSave(snapshotsToUpdate)
	}
}
