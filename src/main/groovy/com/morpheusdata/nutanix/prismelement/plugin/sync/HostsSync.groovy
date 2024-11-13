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
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.nutanix.prismelement.plugin.NutanixPrismElementPlugin
import com.morpheusdata.nutanix.prismelement.plugin.utils.NutanixPrismElementApiService
import groovy.util.logging.Slf4j

/**
 * A class to sync Nutanix hosts to their Morpheus server equivalent
 */
@Slf4j
class HostsSync {
	private final MorpheusContext context
	private final HttpApiClient client
	private final Cloud cloud

	HostsSync(MorpheusContext context, Cloud cloud, HttpApiClient client) {
		this.context = context
		this.cloud = cloud
		this.client = client
	}

	def execute() {
		log.info("Executing hosts sync for cloud ${cloud.name}")
		def authConfig = NutanixPrismElementPlugin.getAuthConfig(context, cloud)
		def listResults = NutanixPrismElementApiService.listHosts(client, authConfig)
		log.debug("$listResults")
		if (listResults.success == true) {
			def objList = listResults.hosts
			def serverType = context.async.cloud.findComputeServerTypeByCode('nutanixMetalHypervisor').blockingGet()
			def serverOs = context.services.osType.find(new DataQuery().withFilter('code', 'linux'))
			def existingItems = context.async.computeServer.listIdentityProjections(new DataQuery()
				.withFilter('refType', 'ComputeZone')
				.withFilter('refId', cloud.id)
				.withFilter('computeServerType.code', "nutanixMetalHypervisor")
			)


			SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> sync = new SyncTask<>(existingItems, objList)
			sync.addMatchFunction { ComputeServerIdentityProjection existingHost, Map cloudHost ->
				existingHost.externalId == cloudHost?.externalId
			}.withLoadObjectDetailsFromFinder {
				context.async.computeServer.listById(it.collect { it.existingItem.id } as List<Long>)
			}.onAdd { addItems ->
				log.debug("Adding ${addItems.size()} servers")
				addServers(addItems, serverType, serverOs)
			}.onUpdate { updateItems ->
				log.debug("Updating ${updateItems.size()} servers")
				updateServers(updateItems)
			}.onDelete { deleteItems ->
				log.debug("Deleting ${deleteItems.size()} servers")
				deleteServers(deleteItems)
			}.start()
		}
	}

	private void addServers(Collection<Map> addItems, ComputeServerType serverType, OsType serverOs) {
		def newServers = []
		for (final def item in addItems) {
			def ipAddress = item.status.resources.hypervisor?.ip
			def newServer = new ComputeServer(
				account: cloud.owner,
				category: "nutanix.host.${cloud.id}",
				name: item.spec.name ?: item.status.name,
				externalId: item.externalId,
				cloud: cloud,
				sshUsername: 'root',
				apiKey: UUID.randomUUID(),
				status: 'provisioned',
				provision: false,
				singleTenant: false,
				serverType: 'hypervisor',
				computeServerType: serverType,
				statusDate: new Date(),
				serverOs: serverOs,
				osType: 'linux',
				hostname: item.spec.name ?: item.status.name,
				sshHost: ipAddress,
				externalIp: ipAddress,
				internalIp: ipAddress,
			)

			def ipmiAddress = item.status.resources.ipmi?.ip
			if (ipmiAddress) {
				def access = new ComputeServerAccess(
					accessType: 'ipmi',
					host: ipmiAddress
				)
				access = context.async.computeServer.access.create(access).blockingGet()
				newServer.accesses << access
			}

			newServer.powerState = (isHostPoweredOn(item)) ? ComputeServer.PowerState.on : ComputeServer.PowerState.off
			newServer.maxMemory = (item.status.resources.memory_capacity_mib ?: 0) * ComputeUtility.ONE_MEGABYTE
			newServer.maxCores = (item.status.resources.num_cpu_cores ?: 0) * (item.status.resources.num_cpu_sockets ?: 0)
			newServer.maxStorage = 0
			newServer.capacityInfo = new ComputeCapacityInfo(
				maxMemory: newServer.maxMemory,
				maxCores: newServer.maxCores,
				maxStorage: newServer.maxStorage)

			newServers << newServer
		}

		context.services.computeServer.bulkCreate(newServers)
	}

	def updateServers(List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems) {
		def serversToUpdate = []
		for (final def item in updateItems) {
			def server = item.existingItem
			def host = item.masterItem
			def shouldUpdate = false

			// Collect all the memory from children to determine this host's used memory
			def childServers = context.services.computeServer.list(
				new DataQuery().withFilters(
					new DataFilter('parentServer', "!=", null),
					new DataFilter<Long>('parentServer.id', item.existingItem.id),
				)
			)
			if (childServers) {
				def combinedChildMemory = childServers.sum {
					it.maxMemory
				} as Long
				if (server.usedMemory != combinedChildMemory) {
					server.usedMemory = combinedChildMemory
					server.capacityInfo?.usedMemory = combinedChildMemory
					shouldUpdate = true
				}
			}

			def maxMemory = (host.status.resources.memory_capacity_mib ?: 0) * ComputeUtility.ONE_MEGABYTE
			if (maxMemory > server.maxMemory) {
				server.maxMemory = maxMemory
				server.capacityInfo?.maxMemory = maxMemory
				shouldUpdate = true
			}

			def maxCores = (host.status.resources.num_cpu_cores ?: 0) * (host.status.resources.num_cpu_sockets ?: 0)
			if (maxCores > server.maxCores) {
				server.maxCores = maxCores
				server.capacityInfo?.maxCores = maxCores
				shouldUpdate = true
			}

			def powerState = isHostPoweredOn(host) ? ComputeServer.PowerState.on : ComputeServer.PowerState.unknown
			if (server.powerState != powerState) {
				server.powerState = powerState
				shouldUpdate = true
			}

			if (shouldUpdate) {
				serversToUpdate << server
			}
		}

		if (serversToUpdate) {
			context.services.computeServer.bulkSave(serversToUpdate)
		}
	}

	private void deleteServers(List<ComputeServerIdentityProjection> deleteItems) {
		for (final def item in deleteItems) {
			// First remove any reference to host from children
			def childServers = context.services.computeServer.list(new DataQuery().withFilter('parentServerId', item.id))
			if (childServers) {
				childServers.collect {
					it.parentServer = null
					it
				}.with {
					context.services.computeServer.bulkSave(it)
				}

			}
		}

		// TODO: switch back to bulkRemove once fixed
		context.async.computeServer.remove(deleteItems).blockingGet()
	}

	/**
	 * Checks if host is powered on
	 *
	 * Note: Original only checked for 'COMPLETE', latest NPE returns 'NORMAL', let's check both for backwards
	 * compatibility.
	 *
	 * @param host nutanix host map
	 * @return true if powered on; false otherwise
	 */
	static boolean isHostPoweredOn(Map host) {
		host.status.state == 'COMPLETE' || host.status.state == 'NORMAL'
	}
}
