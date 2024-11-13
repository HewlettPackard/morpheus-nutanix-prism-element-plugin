package com.morpheusdata.nutanix.prismelement.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.NetworkIdentityProjection
import com.morpheusdata.nutanix.prismelement.plugin.utils.NutanixPrismElementApiService
import groovy.util.logging.Slf4j

@Slf4j
class NetworkSync {
	private MorpheusContext morpheusContext
	private HttpApiClient client
	private Cloud cloud

	NetworkSync(MorpheusContext morpheusContext, Cloud cloud, HttpApiClient client) {
		this.morpheusContext = morpheusContext
		this.cloud = cloud
		this.client = client
	}

	def execute() {
		log.info("Executing network sync for cloud $cloud.name")

		try {
			def codes = ['nutanixVlan', 'nutanixManagedVlan']
			def networkTypes = morpheusContext.services.network.type.list(new DataQuery().withFilter('code', 'in', codes))
			networkTypes?.each {
				log.debug("networkType: ${it.externalType} ${it.code}")
			}
			def listResults = NutanixPrismElementApiService.listNetworks(client, [zone: cloud])
			log.debug("networks: ${listResults}")

			if (listResults.success) {
				def domainRecords = morpheusContext.async.cloud.network.listIdentityProjections(cloud.id)

				SyncTask<NetworkIdentityProjection, Map, Network> syncTask = new SyncTask<>(domainRecords, listResults?.results?.entities)
				syncTask.addMatchFunction { NetworkIdentityProjection morpheusItem, Map cloudItem ->
					morpheusItem?.externalId == cloudItem?.uuid
				}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<NetworkIdentityProjection, Map>> updateItems ->
					log.debug("NetworkSync >> withLoadObjetDetailsFromFinder")
					morpheusContext.async.cloud.network.listById(updateItems.collect { it.existingItem.id } as List<Long>)
				}.onAdd { itemsToAdd ->
					// no projection
					addMissingNetworks(itemsToAdd, networkTypes)
				}.onUpdate {
					// list of update items (Network + map)
					updateMatchedNetworks(it, networkTypes)
				}.onDelete {
					log.debug("NetworkSync >> onDelete")
					// has projection
					morpheusContext.async.cloud.network.bulkRemove(it).blockingGet()
				}.start()
			} else {
				log.error("Error getting networks from listNetworks")
			}
		} catch (e) {
			log.error("NetworkSync execute() error: ${e}", e)
		}
	}

	private void addMissingNetworks(Collection<Map> addList, List<NetworkType> networkTypes) {
		log.debug("NetworkSync >> addMissingNetworks >> called")
		def nutanixVlan = networkTypes?.find { it.code == 'nutanixVlan' }
		def nutanixManagedVlan = networkTypes?.find { it.code == 'nutanixManagedVlan' }
		try {
			def networkAdds = []
			addList?.each {
				log.debug("addList: ${it}")
				def managedNetwork = it.ipConfig?.networkAddress ? true : false
				def networkType = managedNetwork ? nutanixManagedVlan : nutanixVlan
				def add = new Network(
					owner: cloud.owner,
					category: "nutanix.acropolis.network.${cloud.id}",
					name: it.name ?: it.uuid,
					code: "nutanix.acropolis.network.${cloud.id}.${it.uuid}",
					cloud: cloud,
					vlanId: it.vlanId?.toInteger(),
					uniqueId: it.uuid,
					externalId: it.uuid,
					type: networkType,
					refType: 'ComputeZone',
					refId: cloud.id,
					dhcpServer: true,
					active: cloud.defaultNetworkSyncActive
				)
				if (managedNetwork) {
					def poolType = new NetworkPoolType(code: 'nutanix')
					add.prefixLength = it.ipConfig.prefixLength
					add.dhcpIp = it.ipConfig.dhcpServerAddress
					add.dhcpServer = it.ipConfig.dhcpServerAddress?.length() > 0
					add.subnetAddress = it.ipConfig.networkAddress
					add.gateway = it.ipConfig.defaultGateway
					add.tftpServer = it.ipConfig.dhcpOptions?.tftpServerName
					add.bootFile = it.ipConfig.dhcpOptions?.bootFileName
					//add. it.ipConfig.dhcpOptions?.domainSearch
					def poolRanges = it.ipConfig.pool?.collect { range -> range.range }
					def addNetworkPool = new NetworkPool(
						category: "nutanix.acropolis.network.${cloud.id}",
						name: it.ipConfig.networkAddress,
						externalId: it.uuid,
						dnsDomain: it.ipConfig.dhcpOptions?.domainName,
						dnsSearchPath: it.ipConfig.dhcpOptions?.domainSearch,
						dnsServers: [it.ipConfig.dhcpOptions?.domainNameServers],
						refId: cloud.id,
						refType: 'ComputeZone',
						dhcpServer: it.ipConfig.dhcpServerAddress?.length() > 0,
						subnetAddress: it.ipConfig.networkAddress,
						gateway: it.ipConfig.defaultGateway,
						type: poolType,
						owner: cloud.owner,
						account: cloud.account
					)
					//ip ranges
					poolRanges.each { poolRange ->
						def rangeAddrs = poolRange.tokenize(' ')
						if (rangeAddrs.size() > 1) {
							log.debug("NetworkSync >> addMissingNetworks >> adding pool range ${rangeAddrs[0]}-${rangeAddrs[1]}")
							def newRange = new NetworkPoolRange(networkPool: addNetworkPool, startAddress: rangeAddrs[0], endAddress: rangeAddrs[1], externalId: poolRange)
							addNetworkPool.addToIpRanges(newRange)
						}
					}

					// TODO: replace with newer api when fixed, use deprecated api for now
					// The deprecated API properly looks up the poolType by code, which we need, but unfortunately only operates on
					// a list and returns a boolean. If successful, we look the pool back up after the fact.
					if (morpheusContext.async.network.pool.create([addNetworkPool]).blockingGet()) {
						add.pool = morpheusContext.async.network.pool.find(new DataQuery().withFilter('externalId', addNetworkPool.externalId)).blockingGet()
					}
				}
				networkAdds << add
			}
			if (networkAdds.size() > 0) {
				morpheusContext.async.cloud.network.bulkCreate(networkAdds).blockingGet()
			}
		} catch (e) {
			log.error("Error NetworkSync when adding error: ${e}", e)
		}
	}

	private void updateMatchedNetworks(List<SyncTask.UpdateItem<Network, Map>> updateItems, List<NetworkType> networkTypes) {
		log.debug("NetworkSync >> updateMatchedNetworks >> called")
		def nutanixVlan = networkTypes?.find { it.code == 'nutanixVlan' }
		def nutanixManagedVlan = networkTypes?.find { it.code == 'nutanixManagedVlan' }
		def poolType = new NetworkPoolType(code: 'nutanix')
		try {
			def networkUpdates = []
			updateItems?.each {
				def masterItem = it.masterItem
				Network existingItem = it.existingItem
				if (existingItem) {
					def itemChanged = false
					def managedNetwork = masterItem.ipConfig?.networkAddress ? true : false
					def networkType = managedNetwork ? nutanixManagedVlan : nutanixVlan

					if (existingItem.name != (masterItem.name ?: masterItem.uuid)) {
						existingItem.name = masterItem.name ?: masterItem.uuid
						itemChanged = true
					}
					if (existingItem.type != networkType) {
						existingItem.type = networkType
						itemChanged = true
					}
					if (masterItem.ipConfig?.networkAddress) {
						if (existingItem.pool && (existingItem.pool.ipRanges == null || existingItem.pool.ipRanges?.size() == 0)) {
							def poolRanges = masterItem.ipConfig.pool?.collect { range -> range.range }
							if (poolRanges?.size() > 0) {
								poolRanges.each { poolRange ->
									def rangeAddrs = poolRange.tokenize(' ')
									if (rangeAddrs.size() > 1) {
										log.debug("NetworkSync >> updateMatchedNetworks >> adding pool range ${rangeAddrs[0]}-${rangeAddrs[1]}")
										def newRange = new NetworkPoolRange(networkPool: existingItem.pool, startAddress: rangeAddrs[0], endAddress: rangeAddrs[1], externalId: poolRange)
										log.debug("new range: ${newRange}")
										existingItem.pool.addToIpRanges(newRange)
										itemChanged = true
									}
								}
							}
						}
					}
					if (existingItem.pool && existingItem.pool.type == null) {
						existingItem.pool.type = poolType
						itemChanged = true
					}
					if (existingItem.pool && (existingItem.pool.refId == null || existingItem.pool.refId == '')) {
						existingItem.pool.refType = 'ComputeZone'
						existingItem.pool.refId = cloud.id
						checkForDupePools(existingItem.pool, poolType)
						itemChanged = true
					}
					if (itemChanged) {
						networkUpdates << existingItem
					}
				}

				if (networkUpdates.size() > 0) {
					morpheusContext.async.cloud.network.bulkSave(networkUpdates).blockingGet()
				}
			}
		} catch (e) {
			log.error("Error NetworkSync when updating error: ${e}", e)
		}
	}

	private checkForDupePools(NetworkPool pool, NetworkPoolType poolType) {
		try {
			def pools = morpheusContext.services.network.pool.list(
				new DataQuery()
					.withFilter('externalId', pool.externalId)
					.withFilter('type.id', poolType.id)
					.withFilter('id', pool.id)
			)
			def nutanixZone = morpheusContext.services.cloud.find(new DataQuery().withFilter('code', 'nutanix'))
			def dupes = []
			for (dupe in pools) {
				def delete = true
				for (zone in nutanixZone) {
					def poolIds = morpheusContext.services.network.list(
						new DataQuery().withFilter('category', "nutanix.acropolis.network.${zone.id}")
					).collect {it.pool?.id}
					if (poolIds.contains(dupe.id)) {
						delete = false
					}
				}
				if (delete) {
					dupes << dupe
				}
			}

			morpheusContext.services.network.pool.bulkRemove(dupes)
		} catch (e) {
			log.error("checkfordupepools error: ${e}", e)
		}
	}
}
