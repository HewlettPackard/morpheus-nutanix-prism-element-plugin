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

package com.morpheusdata.nutanix.prismelement.plugin.cloud.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.projection.DatastoreIdentity
import com.morpheusdata.nutanix.prismelement.plugin.utils.NutanixPrismElementApiService
import groovy.util.logging.Slf4j

/**
 * A class to sync Nutanix Storage Containers to their Morpheus Datastore equivalent
 */
@Slf4j
class ContainersSync {
	private final MorpheusContext morpheusContext
	private final HttpApiClient client
	private final Cloud cloud

	ContainersSync(MorpheusContext morpheusContext, Cloud cloud, HttpApiClient client) {
		this.morpheusContext = morpheusContext
		this.cloud = cloud
		this.client = client
	}

	def execute() {
		log.info("Executing container sync for cloud ${cloud.name}")
		try {
			def reqConfig = NutanixPrismElementApiService.getRequestConfig(morpheusContext, cloud)
			def listResults = NutanixPrismElementApiService.listContainers(client, reqConfig)
			if (listResults.success == true) {
				def objList = listResults?.containers?.findAll {
					it.name?.toLowerCase() != 'nutanixmanagementshare'
				}

				def existingItems = morpheusContext.async.cloud.datastore.listIdentityProjections(
					new DataQuery()
						.withFilter('refType', 'ComputeZone')
						.withFilter('refId', cloud.id)
						.withFilter('type', 'generic')
				)

				SyncTask<DatastoreIdentity, Map, Datastore> syncTask = new SyncTask<>(existingItems, objList)
				syncTask.addMatchFunction { DatastoreIdentity datastoreIdentity, Map cloudItem ->
					datastoreIdentity?.externalId == cloudItem?.uuid
				}.onAdd { List<Map> cloudItems ->
					def datastores = cloudItems?.collect { cloudItem ->
						log.debug("adding boot policy: ${cloudItem}")
						new Datastore(
							owner: cloud.owner,
							code: "nutanix.acropolis.datastore.${cloud.id}.${cloudItem.id}",
							cloud: cloud,
							category: "nutanix.acropolis.datastore.${cloud.id}",
							name: cloudItem.name,
							internalId: cloudItem.id,
							externalId: cloudItem.uuid,
							refType: 'ComputeZone',
							refId: cloud.id,
							storageSize: Long.valueOf(cloudItem.maxStorage ?: 0),
							freeSpace: Long.valueOf(cloudItem.freeStorage ?: 0),
							active: cloud.defaultDatastoreSyncActive
						)
					}

					morpheusContext.services.cloud.datastore.bulkCreate(datastores)
				}.onDelete { List<Datastore> morpheusItems ->
					log.debug("removing datastores: ${morpheusItems}")
					morpheusContext.services.cloud.datastore.bulkRemove(morpheusItems)
				}.onUpdate {
					// do nothing
				}.withLoadObjectDetailsFromFinder { updateItems ->
					morpheusContext.async.cloud.datastore.listById(updateItems.collect { it.existingItem.id } as List<Long>)
				}.start()
			}
		} catch (e) {
			log.error("ContainersSync error: ${e}", e)
		}
	}
}
