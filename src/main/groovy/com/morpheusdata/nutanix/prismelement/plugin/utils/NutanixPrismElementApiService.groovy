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

package com.morpheusdata.nutanix.prismelement.plugin.utils

import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.MorpheusUtils
import com.morpheusdata.model.Cloud
import groovy.util.logging.Slf4j
import org.apache.http.client.utils.URIBuilder

/**
 * API service for interfacing with Nutanix Prism Element (NPE)
 *
 * Helpful links:
 * - What is each API version for? -- https://www.nutanix.dev/api-versions/
 */
@Slf4j
class NutanixPrismElementApiService {

	static standardApi = '/PrismGateway/services/rest/v1/'
	static v2Api = '/api/nutanix/v2.0/'
	static betaApi = '/api/nutanix/v0.8/'
	static Integer WEB_CONNECTION_TIMEOUT = 120 * 1000

	static testConnection(HttpApiClient client, Map opts) {
		def rtn = [success: false, invalidLogin: false]
		try {
			def apiUrl = getNutanixApiUrl(opts.zone)
			def username = getNutanixUsername(opts.zone)
			def password = getNutanixPassword(opts.zone)
			def headers = buildHeaders(null, username, password)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
			def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/networks/', null, null, requestOpts, 'GET')
			rtn.success = results?.success && results?.error != true

			if (!rtn.success) {
				rtn.invalidLogin = results.statusCode == 401
			}
		} catch (e) {
			log.error("error in testConnection: ${e}", e)
		}
		rtn
	}

	static toList(value) {
		[value].flatten()
	}

	static validateServerConfig(Map opts = [:]) {
		log.debug("validateServerConfig: ${opts}")
		def rtn = [success: false, errors: []]
		try {
			// def zone = ComputeZone.read(opts.zoneId)
			if (opts.networkId) {
				// great
			} else if (opts?.networkInterfaces) {
				// JSON (or Map from parseNetworks)
				log.debug("validateServerConfig networkInterfaces: ${opts?.networkInterfaces}")
				opts?.networkInterfaces?.eachWithIndex { nic, index ->
					def networkId = nic.network?.id ?: nic.network.group
					log.debug("network.id: ${networkId}")
					if (!networkId) {
						rtn.errors << [field: 'networkInterface', msg: 'Network is required']
					}
					if (nic.ipMode == 'static' && !nic.ipAddress) {
						rtn.errors = [field: 'networkInterface', msg: 'You must enter an ip address']
					}
				}
			} else if (opts?.networkInterface && !opts?.networkInterface?.network?.id instanceof String) {
				// UI params
				log.debug("validateServerConfig networkInterface: ${opts.networkInterface}")
				toList(opts?.networkInterface?.network?.id)?.eachWithIndex { networkId, index ->
					log.debug("network.id: ${networkId}")
					if (networkId?.length() < 1) {
						rtn.errors << [field: 'networkInterface', msg: 'Network is required']
					}
					if (networkInterface[index].ipMode == 'static' && !networkInterface[index].ipAddress) {
						rtn.errors = [field: 'networkInterface', msg: 'You must enter an ip address']
					}
				}
			} else {
				rtn.errors << [field: 'networkId', msg: 'Network is required']
			}
			if (opts.containsKey('imageId') && !opts.imageId)
				rtn.errors += [field: 'imageId', msg: 'You must choose an image']
			if (opts.containsKey('nodeCount') && (!opts.nodeCount || opts.nodeCount == '')) {
				rtn.errors += [field: 'config.nodeCount', msg: 'You must enter a Host Count']
				rtn.errors += [field: 'nodeCount', msg: 'You must enter a Host Count']
			}
			rtn.success = (rtn.errors.size() == 0)
			log.debug("validateServer results: ${rtn}")
		} catch (e) {
			log.error("error in validateServerConfig: ${e}", e)
		}
		return rtn
	}

	static listContainers(HttpApiClient client, Map authConfig) {
		def rtn = [success: false, containers: []]
		//set the path for the different api versions
		def apiPath
		def apiMethod = 'GET'
		if (authConfig.apiNumber > 2)
			apiPath = ('/api/nutanix/v2.0/storage_containers')
		else if (authConfig.apiNumber > 1)
			apiPath = ('/api/nutanix/' + authConfig.apiVersion + '/storage_containers')
		else
			apiPath = ('/PrismGateway/services/rest/' + authConfig.apiVersion + '/containers/')
		//make api call
		def headers = buildHeaders(null, authConfig.username, authConfig.password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(authConfig.apiUrl, apiPath, null, null, requestOpts, apiMethod)
		rtn.success = results?.success && results?.error != true
		if (rtn.success == true) {
			rtn.results = results.data
			//results depend on version - grr nutanix
			results.data?.entities?.each { entity ->
				if (authConfig.apiNumber > 1) {
					rtn.containers << [id        : entity.id, uuid: entity.storage_container_uuid, clusterUuid: entity.cluster_uuid, name: entity.name,
									   maxStorage: entity.max_capacity, replicationFactor: entity.replication_factor, freeStorage: entity?.usage_stats?.'storage.free_bytes']
				} else {
					rtn.containers << [id        : entity.id, uuid: entity.containerUuid, clusterUuid: entity.clusterUuid, name: entity.name,
									   maxStorage: entity.maxCapacity, replicationFactor: entity.replicationFactor, freeStorage: entity?.usageStats?.'storage.free_bytes']
				}
			}
			log.debug("listContainers: ${rtn}")
		}
		return rtn
	}

	static listImages(HttpApiClient client, Map authConfig) {
		def rtn = [success: false, images: []]
		//set the path for the different api versions
		def apiPath
		def apiMethod = 'GET'
		if (authConfig.apiNumber > 2)
			apiPath = ('/api/nutanix/v2.0/images')
		else if (authConfig.apiNumber > 1)
			apiPath = ('/api/nutanix/' + authConfig.apiVersion + '/images')
		else
			apiPath = ('/api/nutanix/' + authConfig.apiVersion + '/images/')
		//call the api
		def headers = buildHeaders(null, authConfig.username, authConfig.password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(authConfig.apiUrl, apiPath, null, null, requestOpts, apiMethod)
		rtn.success = results?.success && results?.error != true
		if (rtn.success == true) {
			results.data?.entities?.each { entity ->
				if (authConfig.apiNumber > 1) {
					def row = [uuid       : entity.uuid, name: entity.name, imageStatus: entity.image_state, vmDiskId: entity.vm_disk_id, externalId: entity.vm_disk_id,
							   containerId: entity.storage_container_id, containerUuid: entity.storage_container_uuid, deleted: entity.deleted, timestamp: entity.logical_timestamp]
					row.imageType = (entity.image_type == 'DISK_IMAGE' || entity.image_type?.toLowerCase() == 'disk' || entity.image_type == null) ? 'qcow2' : 'iso'
					rtn.images << row
				} else {
					def row = [uuid       : entity.uuid, name: entity.name, imageStatus: 'Active', vmDiskId: entity.vmDiskId, externalId: entity.vmDiskId,
							   containerId: entity.containerId]
					row.imageType = (entity.imageType == 'DISK_IMAGE' || entity.imageType?.toLowerCase() == 'disk' || entity.imageType == null) ? 'qcow2' : 'iso'
					rtn.images << row
				}
			}
			log.debug("listImages: ${rtn.images}")
		}
		return rtn
	}

	static findImage(HttpApiClient client, opts, name) {
		def rtn = [success: false, image: null]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/images/', null, null, requestOpts, 'GET')
		//rtn.success = results?.success && results?.error != true
		if (results.success == true) {
			rtn.results = results.data //new groovy.json.JsonSlurper().parseText(results.content)
			rtn.results.entities?.each { entity ->
				log.debug("find image entity: ${entity} for: ${name}")
				if (rtn.success == false) {
					if (entity.name == name) {
						rtn.image = entity
						rtn.success = true
					}
				}
			}
			log.debug("results: ${rtn.results}")
		}
		return rtn
	}

	static checkImageId(HttpApiClient client, opts, imageId) {
		try {
			if (findImageId(client, opts, imageId)?.success) {
				return imageId
			}
		} catch (ex) {
			log.error("Error checking for imageId", ex)
		}
		return null
	}

	static findImageId(HttpApiClient client, opts, imageId) {
		def rtn = [success: false, image: null]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/images/', null, null, requestOpts, 'GET')

		//rtn.success = results?.success && results?.error != true
		if (results.success == true) {
			rtn.results = results.data //new groovy.json.JsonSlurper().parseText(results.content)
			rtn.results.entities?.each { entity ->
				log.debug("find image entity: ${entity} for: ${name}")
				if (rtn.success == false) {
					if (entity.vmDiskId == imageId) {
						rtn.image = entity
						rtn.success = true
					}
				}
			}
			log.debug("results: ${rtn.results}")
		}
		return rtn
	}

	static loadImage(HttpApiClient client, opts, imageId) {
		def rtn = [success: false, image: null]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/images/' + imageId + '/', null, null, requestOpts, 'GET')
		log.debug("results: ${results}")
		//rtn.success = results?.success && results?.error != true
		if (results.success == true) {
			rtn.image = results.data
			rtn.success = results.success
		}
		return rtn
	}

	static listTasks(HttpApiClient client, opts) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def headers = buildHeaders(null, username, password)
		def requestOpts = [headers: headers, proxySettings: opts.proxySettings]
		def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/tasks/', null, null, requestOpts, 'GET')
		rtn.success = results?.success && results?.error != true
		if (rtn.success == true) {
			rtn.results = results.data //new groovy.json.JsonSlurper().parseText(results.content)
			log.debug("task results: ${rtn.results}")
		}
		return rtn
	}

	static getTask(HttpApiClient client, opts, taskId) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(apiUrl, '/api/nutanix/v2.0/tasks/' + taskId, null, null, requestOpts, 'GET')
		rtn.success = results?.success && results?.error != true

		if (rtn.success == true) {
			rtn.results = results.data //new groovy.json.JsonSlurper().parseText(results.content)
			log.debug("task results: ${rtn.results}")
		} else if (results?.error == true) {
			rtn.errorCode = results.errorCode
			rtn.error == true
		}
		return rtn
	}

	static listStoragePools(HttpApiClient client, opts) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/storage_pool/', null, null, requestOpts, 'GET')
		rtn.success = results?.success && results?.error != true
		if (rtn.success == true) {
			rtn.results = results.data //new groovy.json.JsonSlurper().parseText(results.content)
			log.debug("results: ${rtn.results}")
		}
		return rtn
	}

	static listVirtualMachinesV1(HttpApiClient client, Map authConfig, Map opts) {
		def rtn = [success: false, virtualMachines: [], total: 0]
		try {
			def apiPath = standardApi + 'vms'
			def headers = buildHeaders(null, authConfig.username, authConfig.password)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
			//page it
			def results = client.callJsonApi(authConfig.apiUrl, apiPath, null, null, requestOpts, 'GET')
			if (results.success == true) {
				results.data?.entities?.each { row ->
					def obj = row
					obj.externalId = row.uuid
					rtn.virtualMachines << obj
				}
				rtn.success = true
			}
		} catch (e) {
			log.error("error listing virtual machines: ${e}", e)
		}
		return rtn
	}

	static listVirtualMachines(HttpApiClient client, Map authConfig, Map opts) {
		def rtn = [success: false, virtualMachines: [], total: 0]
		try {
			def apiPath = authConfig.basePath + 'vms/list'
			def headers = buildHeaders(null, authConfig.username, authConfig.password)
			def perPage = opts.perPage ?: 50
			def apiBody = [kind: 'vm', offset: 0, length: perPage]
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: apiBody)
			//get v1 data
			def offset = 0
			def vmList = listVirtualMachinesV2(client, authConfig, opts)
			//page it
			def keepGoing = true
			while (keepGoing) {
				def results = client.callJsonApi(authConfig.apiUrl, apiPath, null, null, requestOpts, 'POST')
				if (results.success == true) {
					results.data?.entities?.each { row ->
						def obj = row
						obj.externalId = row.metadata?.uuid
						obj.legacyVm2 = vmList.virtualMachines.find { it.externalId == obj.externalId }
						obj.legacyVm = obj.legacyVm2.legacyVm
						//log.debug("legacyVm: ${obj.legacyVm}")
						rtn.virtualMachines << obj
					}
					if (results.data?.metadata?.offset != null && results.data?.metadata?.total_matches > rtn.virtualMachines.size()) {
						offset += perPage
						def apiBody2 = [kind: 'vm', offset: offset, length: perPage]
						requestOpts.body = apiBody2
					} else {
						keepGoing = false
						rtn.total = rtn.virtualMachines?.size()
					}
				} else {
					keepGoing = false
					rtn.msg = results.msg
				}
				rtn.success = true
			}
		} catch (e) {
			log.error("error listing virtual machines: ${e}", e)
		}
		return rtn
	}

	static listVirtualMachinesV2(HttpApiClient client, Map authConfig, Map opts) {
		def rtn = [success: false, virtualMachines: [], total: 0]
		try {
			def apiPath = v2Api + 'vms'
			def headers = buildHeaders(null, authConfig.username, authConfig.password)

			def query = [include_vm_disk_config: true, include_vm_nic_config: true]
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, query: query)
			//get v1 data
			def vmList = listVirtualMachinesV1(client, authConfig, opts)
			def results = client.callJsonApi(authConfig.apiUrl, apiPath, null, null, requestOpts, 'GET')
			//page it
			def keepGoing = true
			if (results.success == true) {
				results.data?.entities?.each { row ->
					def obj = row
					obj.externalId = row.uuid
					obj.legacyVm = vmList.virtualMachines.find { it.externalId == obj.externalId }
					//log.debug("legacyVm: ${obj.legacyVm}")
					rtn.virtualMachines << obj
				}
				rtn.total = rtn.virtualMachines?.size()
				rtn.success = true
			} else {
				rtn.msg = results.msg
				rtn.success = false
			}

		} catch (e) {
			log.error("error listing virtual machines: ${e}", e)
		}
		return rtn
	}

	static listSnapshotsV2(HttpApiClient client, Map authConfig, Map opts) {
		def rtn = [success: false, snapshots: [], total: 0]
		try {
			def apiPath = v2Api + 'snapshots'
			def headers = buildHeaders(null, authConfig.username, authConfig.password)

			def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
			def results = client.callJsonApi(authConfig.apiUrl, apiPath, null, null, requestOpts, 'GET')
			//page it
			def keepGoing = true
			if (results.success == true) {
				results.data?.entities?.each { row ->
					def obj = row

					rtn.snapshots << obj
				}
				rtn.total = rtn.snapshots?.size()
				rtn.success = true
			} else {
				rtn.msg = results.msg
				rtn.success = false
			}

		} catch (e) {
			log.error("error listing virtual machines: ${e}", e)
		}
		return rtn
	}


	static findVirtualMachine(HttpApiClient client, opts, name) {
		def rtn = [success: false, virtualMachine: null]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/vms/', null, null, requestOpts, 'GET')
		//rtn.success = results?.success && results?.error != true
		if (results.success == true) {
			rtn.results = results.data //new groovy.json.JsonSlurper().parseText(results.content)
			rtn.results.entities?.each { entity ->
				log.debug("find vm entity: ${entity} for: ${name}")
				if (rtn.success == false) {
					if (entity.config?.name == name) {
						rtn.virtualMachine = entity
						rtn.success = true
					}
				}
			}
			log.debug("findVirtualMachines results: ${rtn.results}")
		}
		return rtn
	}

	static findVirtualMachineId(HttpApiClient client, opts, vmId) {
		def rtn = [success: false, virtualMachine: null]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/vms/', null, null, requestOpts, 'GET')

		//rtn.success = results?.success && results?.error != true
		if (results.success == true) {
			rtn.results = results.data //new groovy.json.JsonSlurper().parseText(results.content)
			rtn.results.entities?.each { entity ->
				log.debug("find vm entity: ${entity} for: ${vmId}")
				if (rtn.success == false) {
					if (entity.uuid == vmId) {
						rtn.virtualMachine = entity
						rtn.success = true
					}
				}
			}
			log.debug("findVirtualMachineId results: ${rtn.results}")
		}
		return rtn
	}

	static loadVirtualMachine(HttpApiClient client, opts, vmId) {
		def rtn = [success: false, virtualMachine: null]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)

		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/vms/' + vmId, null, null, requestOpts, 'GET')

		//rtn.success = results?.success && results?.error != true
		if (results.success == true && results.error != true) {
			rtn.results = results.data //new groovy.json.JsonSlurper().parseText(results.content)
			def vmResults = client.callJsonApi(apiUrl, '/PrismGateway/services/rest/v1/vms/' + vmId, null, null, requestOpts, 'GET')
			rtn.vmResults = vmResults.data
			rtn.virtualMachine = rtn.results
			rtn.vmDetails = rtn.vmResults
			rtn.success = vmResults.success
		}
		return rtn
	}

	static getVirtualMachineDisks(HttpApiClient client, opts, vmId) {
		def rtn = [success: false, disks: []]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def query = [includeDiskSizes: true]
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers, query: query)
		def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/vms/' + vmId + '/disks', null, null, requestOpts, 'GET')

		if (results.success == true) {
			rtn.success = true
			rtn.results = results.data //new groovy.json.JsonSlurper().parseText(results.content)
			log.debug("getVirtualMachineDisks results: ${rtn.results}")
			rtn.results.entities?.each { entity ->
				rtn.disks << entity
			}
		}
		return rtn
	}

	static getVirtualMachineNics(HttpApiClient client, opts, vmId) {
		def rtn = [success: false, nics: []]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def query = [includeAddressAssignments: true]
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers, query: query)
		def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/vms/' + vmId + '/nics', null, null, requestOpts, 'GET')
		if (results.success == true) {
			rtn.success = true
			rtn.results = results.data //new groovy.json.JsonSlurper().parseText(results.content)
			log.debug("getVirtualMachineNics results: ${rtn.results}")
			rtn.results.entities?.each { entity ->
				rtn.nics << entity
			}
		}
		return rtn
	}

	static listNetworks(HttpApiClient client, opts) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/networks/', null, null, requestOpts, 'GET')
		rtn.success = results?.success && results?.error != true
		if (rtn.success == true) {
			rtn.results = results.data //new groovy.json.JsonSlurper().parseText(results.content)
			log.debug("network results: ${rtn.results}")
		}
		return rtn
	}

	static listHostsV1(HttpApiClient client, opts) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/hosts/', null, null, requestOpts, 'GET')
		rtn.success = results?.success && results?.error != true
		if (rtn.success == true) {
			rtn.results = results.data //new groovy.json.JsonSlurper().parseText(results.content)
			log.debug("results: ${rtn.results}")
		}
		return rtn
	}

	static listHosts(HttpApiClient client, Map authConfig, Map opts) {
		def rtn = [success: false, hosts: [], total: 0]
		try {
			def apiPath = authConfig.basePath + 'hosts/list'
			def headers = buildHeaders(null, authConfig.username, authConfig.password)
			def apiBody = [kind: 'host']
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: apiBody)
			//page it
			def results = client.callJsonApi(authConfig.apiUrl, apiPath, null, null, requestOpts, 'POST')
			if (results.success == true) {
				results.data?.entities?.each { row ->
					def obj = row
					obj.externalId = row.metadata.uuid
					rtn.hosts << obj
					//println("host: ${obj}")
				}
				rtn.success = true
			}
		} catch (e) {
			log.error("error listing hosts: ${e}", e)
		}
		return rtn
	}

	static uploadImage(HttpApiClient client, opts) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def imageUrl
		def image = opts.image
		if (image.imageUrl) {
			imageUrl = image.imageUrl
		} else if (image.imageFile) {
			def imageFile = image.imageFile
			imageUrl = imageFile.getURL(new Date(new Date().time + 20l * 60000l)).toString()
		}
		log.debug "uploadImage imageUrl: ${imageUrl}"
		imageUrl = imageUrl.replaceAll(" ", "%20")
		def containerId = opts.containerId
		def body = [name: image.name, image_type: image.imageType == 'iso' ? 'iso' : 'disk_image', image_import_spec: [storage_container_uuid: containerId, url: imageUrl]]
		log.info("Upload Image Body: ${body}")
		//cache
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
		def imageUploadUrl = v2Api + "images"
		def results = client.callJsonApi(apiUrl, imageUploadUrl, null, null, requestOpts, 'POST')
		log.debug("uploadImage: ${results}")
		rtn.success = results?.success && results?.error != true
		if (rtn.success == true) {
			rtn.results = results.data
			rtn.taskUuid = rtn.results.task_uuid
			log.debug("results: ${rtn.results}")
		}
		return rtn
	}

	static cloneVmToImage(HttpApiClient client, Map authConfig, Map cloneConfig) {
		def rtn = [success: false]
		//api auth
		def apiUrl = authConfig.apiUrl
		def username = authConfig.username
		def password = authConfig.password
		//config
		def imageName = cloneConfig.name
		def snapshotId = cloneConfig.snapshotId
		//get the snapshot
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def snapshotResults = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/snapshots/' + snapshotId, null, null, requestOpts, 'GET')
		println("snapshotResults: ${snapshotResults}")
		if (snapshotResults?.success && snapshotResults?.error != true) {
			def snapshotInfo = snapshotResults.data //new groovy.json.JsonSlurper().parseText(results.content)
			log.debug("snapshot info: ${rtn.results}")
			def vmDisks = snapshotInfo.vmCreateSpecification.vmDisks?.findAll { it.isCdrom != true }
			println("disks: ${vmDisks}")
			def vmDiskId = vmDisks?.size() > 0 ? vmDisks[0].vmDiskClone?.vmDiskUuid : null
			def containerId = vmDisks?.size() > 0 ? vmDisks[0].vmDiskClone?.containerUuid : null
			rtn.containerId = containerId
			//throw error if null
			//groupUuid
			def body = [
				name       : cloneConfig.name,
				imageType  : 'disk_image',
				vmDiskClone: [
					containerUuid  : containerId,
					snapshotGroupId: snapshotInfo.groupUuid,
					vmDiskUuid     : vmDiskId
				]
			]
			log.info("clone to template body: ${body}")
			//clone to template
			def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/images', null, null, requestOpts + [body: body], 'POST')
			log.debug("cloneVmToImage: ${results}")
			rtn.success = results?.success && results?.error != true
			if (rtn.success == true) {
				rtn.results = results.data //new groovy.json.JsonSlurper().parseText(results.content)
				rtn.taskUuid = rtn.results.taskUuid
				log.debug("results: ${rtn.results}")
			} else if (results?.error == true) {
				rtn.errorCode = results.errorCode
				rtn.error == true
			}
		} else if (snapshotResults?.error == true) {
			rtn.errorCode = snapshotResults.errorCode
			rtn.error == true
		}
		return rtn
	}

	static createServer(HttpApiClient client, opts) {
		def rtn = [success: false]
		if (!opts.imageId) {
			rtn.error = 'Please specify an image type'
		} else if (!opts.name) {
			rtn.error = 'Please specify a name'
		} else {
			def apiVersion = opts.zone.serviceVersion
			def body
			if (MorpheusUtils.compareVersions(apiVersion, 'v2.0') >= 0) {
				rtn = createServerUsingV2Api(client, opts)
			} else {
				rtn = createServerUsingStandardApi(client, opts)
			}
		}
		return rtn
	}

	private static createServerUsingStandardApi(HttpApiClient client, opts) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def containerId = opts.containerId
		def osDiskSize = (int) opts.maxStorage.div(ComputeUtility.ONE_MEGABYTE)
		def maxMemory = (int) opts.maxMemory.div(ComputeUtility.ONE_MEGABYTE)

		def osDisk = [vmDiskClone: [vmDiskUuid: opts.imageId, minimumSizeMb: osDiskSize]]
		def rootType = opts.rootVolume?.type?.name
		def diskTypes = [rootType]
		if (rootType == 'sata')
			osDisk.diskAddress = [deviceBus: 'sata']
		else if (rootType == 'ide')
			osDisk.diskAddress = [deviceBus: 'ide']
		else if (rootType == 'scsi')
			osDisk.diskAddress = [deviceBus: 'scsi']
		def vmDisks = [osDisk]
		if (opts.diskSize) {
			def dataDiskSize = (int) opts.diskSize.div(ComputeUtility.ONE_MEGABYTE)
			vmDisks << [vmDiskCreate: [sizeMb: dataDiskSize, containerUuid: containerId]]
		} else if (opts.dataDisks?.size() > 0) {
			opts.dataDisks?.each { disk ->
				def diskContainerId
				if (disk.datastore?.externalId) {
					diskContainerId = disk.datastore.externalId
				}
				def dataDiskSize = (int) disk.maxStorage.div(ComputeUtility.ONE_MEGABYTE)
				def vmDataDisk = [vmDiskCreate: [sizeMb: dataDiskSize, containerUuid: diskContainerId ?: containerId]]
				def diskType = disk.type?.name
				diskTypes << diskType
				if (diskType == 'sata')
					vmDataDisk.diskAddress = [deviceBus: 'sata']
				else if (diskType == 'ide')
					vmDataDisk.diskAddress = [deviceBus: 'ide']
				else if (diskType == 'scsi')
					vmDataDisk.diskAddress = [deviceBus: 'scsi']
				vmDisks << vmDataDisk
			}
		}
		diskTypes = diskTypes?.unique()
		if (opts.cloudFileId)
			vmDisks << [isCdrom: true, vmDiskClone: [vmDiskUuid: opts.cloudFileId, minimumSize: (ComputeUtility.ONE_MEGABYTE)]]
		//def cloudInitDisk = [vmDiskClone:[vmDiskUuid:1]]
		def vmNics = []
		//nic network
		if (opts.networkConfig?.primaryInterface?.network?.uniqueId) { //new style multi network
			def vmNic = [networkUuid: opts.networkConfig.primaryInterface.network.uniqueId]
			if (opts.networkConfig?.primaryInterface?.ipAddress && opts.networkConfig?.primaryInterface?.network?.type?.code?.contains('Managed')) {
				vmNic.requestedIpAddress = opts.networkConfig.primaryInterface.ipAddress
				vmNic.requestIp = true
			}
			if (opts.networkConfig.primaryInterface.type?.code == 'nutanix.E1000')
				vmNic.model = 'e1000'
			vmNics << vmNic
			//extra networks
			opts.networkConfig.extraInterfaces?.each { extraInterface ->
				if (extraInterface.network?.uniqueId) {
					vmNic = [networkUuid: extraInterface.network?.uniqueId]
					if (extraInterface?.ipAddress && extraInterface?.network?.type?.code?.contains('Managed')) {
						vmNic.requestedIpAddress = extraInterface.ipAddress
						vmNic.requestIp = true
					}
					if (extraInterface.type?.code == 'nutanix.E1000')
						vmNic.model = 'e1000'
					vmNics << vmNic
				}
			}
		} else if (opts.networkId) { //old style
			def vmNic = [networkUuid: opts.networkId]
			if (opts.ipAddress) {
				vmNic.requestedIpAddress = opts.ipAddress
				vmNic.requestIp = true
			}
			vmNics << vmNic
		}
		//create request body
		def numVcpus = ((opts.maxCores ?: 1) / (opts.coresPerSocket ?: 1)).toLong()
		def body = [memoryMb       : ((int) maxMemory),
					name           : opts.name,
					numVcpus       : numVcpus,
					numCoresPerVcpu: opts.coresPerSocket ?: 1,
					vmNics         : vmNics,
					vmDisks        : vmDisks
		]
		if (opts.cloudConfig) {
			body.vmCustomizationConfig = [
				userdata: opts.cloudConfig
			]
		}
		if (opts.uuid)
			body.uuid = opts.uuid
		//set the boot config if more than one bus types
		if (diskTypes.size() > 1 || opts.uefi == true) {
			body.bootConfig = [bootDeviceType: 'disk', diskAddress: [deviceBus: rootType, deviceIndex: 0]]
		}
		if (opts.uefi == true) {
			body.bootConfig = body.bootConfig ?: [:]
			body.bootConfig['secureBoot'] = true
			body.bootConfig['uefiBoot'] = true
			body.machineType = "Q35"
		}
		log.debug("create server body: ${body}")
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
		def results = client.callJsonApi(apiUrl, standardApi + 'vms', null, null, requestOpts, 'POST')
		log.info("createServer: ${results}")
		//rtn.success = results?.success && results?.error != true
		if (results.success == true && results.data?.taskUuid) {
			def taskId = results.data.taskUuid
			def taskResults = checkTaskReady(opts, taskId)
			if (taskResults.success != true && taskResults.errorCode == 500 && opts.uuid) {
				def vmCheckResults = checkVmReady(opts, opts.uuid)
				if (vmCheckResults.success == true)
					taskResults = [success: true, error: false, results: [entityList: [[uuid: opts.uuid]], entity_list: [[entity_id: opts.uuid]]]]
			}
			if (taskResults.success == true && taskResults.error != true) {
				def serverId = taskResults.results.entity_list[0].entity_id
				def serverResults = findVirtualMachineId(opts, serverId)
				if (serverResults.success == true) {
					def vm = serverResults?.virtualMachine
					if (vm) {
						rtn.taskUuid = taskId
						rtn.results = vm
						rtn.serverId = vm.uuid
						rtn.completeTime = taskResults.completeTime
						rtn.success = true
					}
				} else {
					rtn.msg = 'could not find template'
				}
			} else {
				rtn.msg = 'task failed'
			}
		} else {
			log.error("error creating nutanix vm: ${results}")
			rtn.msg = 'error creating nutanix vm'
		}
		return rtn
	}

	private static createServerUsingV2Api(HttpApiClient client, opts) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def containerId = opts.containerId
		def osDiskSize = (long) opts.maxStorage
		def maxMemory = (int) opts.maxMemory.div(ComputeUtility.ONE_MEGABYTE)

		def osDisk = [vm_disk_clone: [disk_address: [vmdisk_uuid: opts.imageId], minimum_size: osDiskSize]]
		def rootType = opts.rootVolume?.type?.name
		def diskTypes = [rootType]
		if (rootType == 'sata') {
			osDisk.disk_address = osDisk.disk_address ?: [:]
			osDisk.disk_address['device_bus'] = 'SATA'
		} else if (rootType == 'ide') {
			osDisk.disk_address = osDisk.disk_address ?: [:]
			osDisk.disk_address['device_bus'] = 'IDE'
		} else if (rootType == 'scsi') {
			osDisk.disk_address = osDisk.disk_address ?: [:]
			osDisk.disk_address['device_bus'] = 'SCSI'
		}
		def vmDisks = [osDisk]
		if (opts.diskSize) {
			def dataDiskSize = (long) opts.diskSize
			vmDisks << [vm_disk_create: [size: dataDiskSize, storage_container_uuid: containerId]]
		} else if (opts.dataDisks?.size() > 0) {
			opts.dataDisks?.each { disk ->
				def diskContainerId
				if (disk.datastore?.externalId) {
					diskContainerId = disk.datastore.externalId
				}
				def dataDiskSize = (long) disk.maxStorage
				def vmDataDisk = [vm_disk_create: [size: dataDiskSize, storage_container_uuid: diskContainerId ?: containerId]]
				def diskType = disk.type?.name
				diskTypes << diskType
				if (rootType == 'sata') {
					vmDataDisk.disk_address = vmDataDisk.disk_address ?: [:]
					vmDataDisk.disk_address['device_bus'] = 'SATA'
				} else if (rootType == 'ide') {
					vmDataDisk.disk_address = vmDataDisk.disk_address ?: [:]
					vmDataDisk.disk_address['device_bus'] = 'IDE'
				} else if (rootType == 'scsi') {
					vmDataDisk.disk_address = vmDataDisk.disk_address ?: [:]
					vmDataDisk.disk_address['device_bus'] = 'SCSI'
				}
				vmDisks << vmDataDisk
			}
		}
		diskTypes = diskTypes?.unique()
		if (opts.cloudFileId)
			vmDisks << [is_cdrom: true, vm_disk_clone: [disk_address: [vmdisk_uuid: opts.cloudFileId], minimum_size: (ComputeUtility.ONE_MEGABYTE)]]
		//def cloudInitDisk = [vmDiskClone:[vmDiskUuid:1]]
		def vmNics = []
		//nic network
		if (opts.networkConfig?.primaryInterface?.network?.uniqueId) { //new style multi network
			def vmNic = [network_uuid: opts.networkConfig.primaryInterface.network.uniqueId]
			if (opts.networkConfig?.primaryInterface?.ipAddress && opts.networkConfig?.primaryInterface?.network?.type?.code?.contains('Managed')) {
				vmNic.requested_ip_address = opts.networkConfig.primaryInterface.ipAddress
				vmNic.request_ip = true
			}
			if (opts.networkConfig.primaryInterface.type?.code == 'nutanix.E1000') {
				vmNic.model = 'e1000'
			}
			vmNics << vmNic
			//extra networks
			opts.networkConfig.extraInterfaces?.each { extraInterface ->
				if (extraInterface.network?.uniqueId) {
					vmNic = [network_uuid: extraInterface.network?.uniqueId]
					if (extraInterface?.ipAddress && extraInterface?.network?.type?.code?.contains('Managed')) {
						vmNic.requested_ip_address = extraInterface.ipAddress
						vmNic.request_ip = true
					}
					if (extraInterface.type?.code == 'nutanix.E1000') {
						vmNic.model = 'e1000'
					}
					vmNics << vmNic
				}
			}
		} else if (opts.networkId) { //old style
			def vmNic = [network_uuid: opts.networkId]
			if (opts.ipAddress) {
				vmNic.requested_ip_address = opts.ipAddress
				vmNic.request_ip = true
			}
			vmNics << vmNic
		}
		//create request body
		def numVcpus = ((opts.maxCores ?: 1) / (opts.coresPerSocket ?: 1)).toLong()
		def body = [memory_mb         : ((int) maxMemory),
					name              : opts.name,
					num_vcpus         : numVcpus,
					num_cores_per_vcpu: opts.coresPerSocket ?: 1,
					vm_nics           : vmNics,
					vm_disks          : vmDisks
		]
		if (opts.cloudConfig) {
			body.vm_customization_config = [
				userdata: opts.cloudConfig
			]
		}
		if (opts.uuid)
			body.uuid = opts.uuid
		//set the boot config if more than one bus types
		if (diskTypes.size() > 1 || opts.uefi == true) {
			body.boot = [boot_device_type: 'disk', disk_address: [device_bus: rootType, device_index: 0]]
		}
		if (opts.uefi == true) {
			body.boot = body.boot ?: [:]
			body.boot['secure_boot'] = true
			body.boot['uefi_boot'] = true
			body.machine_type = "Q35"
		}
		log.debug("create server body: ${body}")

		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
		def results = client.callJsonApi(apiUrl, v2Api + 'vms', null, null, requestOpts, 'POST')
		log.info("createServer: ${results}")
		//rtn.success = results?.success && results?.error != true
		if (results.success == true && results.data?.task_uuid) {
			def taskId = results.data.task_uuid
			def taskResults = checkTaskReady(client, opts, taskId)
			if (taskResults.success != true && taskResults.errorCode == 500 && opts.uuid) {
				def vmCheckResults = checkVmReady(client, opts, opts.uuid)
				if (vmCheckResults.success == true)
					taskResults = [success: true, error: false, results: [entityList: [[uuid: opts.uuid]], entity_list: [[entity_id: opts.uuid]]]]
			}
			if (taskResults.success == true && taskResults.error != true) {
				def serverId = taskResults.results.entity_list[0].entity_id
				def serverResults = findVirtualMachineId(client, opts, serverId)
				if (serverResults.success == true) {
					def vm = serverResults?.virtualMachine
					if (vm) {
						rtn.taskUuid = taskId
						rtn.results = vm
						rtn.serverId = vm.uuid
						rtn.completeTime = taskResults.completeTime
						rtn.success = true
					}
				} else {
					rtn.msg = 'could not find template'
				}
			} else {
				rtn.msg = 'task failed'
			}
		} else {
			log.error("error creating nutanix vm: ${results}")
			rtn.msg = 'error creating nutanix vm'
		}
		return rtn
	}

	static updateServer(HttpApiClient client, opts) {
		log.debug("updateServer ${opts}")
		def rtn = [success: false]
		if (!opts.serverId) {
			rtn.error = 'Please specify a Server ID'
		} else {
			def apiUrl = getNutanixApiUrl(opts.zone)
			def username = getNutanixUsername(opts.zone)
			def password = getNutanixPassword(opts.zone)
			def maxMemory = opts.maxMemory.div(ComputeUtility.ONE_MEGABYTE)
			def maxVcpus = ((opts.maxCores ?: 1) / (opts.coresPerSocket ?: 1)).toLong()
			def body = [memoryMb       : maxMemory,
						numVcpus       : maxVcpus,
						numCoresPerVcpu: (opts.coresPerSocket ?: 1)
			]
			log.info("resize server body: ${body}")
			def headers = buildHeaders(null, username, password)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
			def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/vms/' + opts.serverId, null, null, requestOpts, 'PUT')
			log.info("updateServer: ${results}")
			if (results.success == true && results.data) {
				def taskId = results.data.taskUuid
				def taskResults = checkTaskReady(client, opts, taskId)
				if (taskResults.success == true && taskResults.error != true) {
					rtn.taskUuid = taskId
					rtn.success = true
				} else {
					rtn.msg = 'resize failed'
				}
			}
		}
		return rtn
	}

	static resizeDisk(HttpApiClient client, opts, vmId, diskAddress, diskId, sizeGB) {
		log.debug("resizeDisk ${opts}, vm:${vmId}, disk:${diskId}")
		def rtn = [success: false]
		if (!vmId) {
			rtn.error = 'Please specify a VM ID'
		} else if (!diskAddress) {
			rtn.error = 'Please specify a disk address'
		} else if (!diskId) {
			rtn.error = 'Please specify a disk ID'
		} else if (!sizeGB) {
			rtn.error = 'Please specify a disk size'
		} else {
			def apiUrl = getNutanixApiUrl(opts.zone)
			def username = getNutanixUsername(opts.zone)
			def password = getNutanixPassword(opts.zone)
			def diskSize = (int) sizeGB * ComputeUtility.ONE_GIGABYTE
			def updateSpec = [vmDiskClone: [minimumSize: diskSize, vmDiskUuid: diskId]]
			def body = [updateSpec: updateSpec]
			log.info("resize disk body: ${body}")
			def headers = buildHeaders(null, username, password)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
			def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/vms/' + vmId + '/disks/' + diskAddress, null, null, requestOpts, 'PUT')
			log.info("resizeDisk results: ${results}")
			if (results.success == true && results.data) {
				def taskId = results.data.taskUuid
				def taskResults = checkTaskReady(client, opts, taskId)
				if (taskResults.success == true && taskResults.error != true) {
					rtn.taskUuid = taskId
					rtn.success = true
				} else {
					rtn.msg = 'resize disk failed'
				}
			}
		}
		return rtn
	}


	static ejectDisk(HttpApiClient client, opts, vmId, diskAddress) {
		log.debug("ejectDisk ${opts}, vm:${vmId}")
		def rtn = [success: false]
		if (!vmId) {
			rtn.error = 'Please specify a VM ID'
		} else if (!diskAddress) {
			rtn.error = 'Please specify a disk address'
		} else {
			def apiUrl = getNutanixApiUrl(opts.zone)
			def username = getNutanixUsername(opts.zone)
			def password = getNutanixPassword(opts.zone)
			def updateSpec = [isEmpty: true]
			def body = [updateSpec: updateSpec]
			log.info("resize disk body: ${body}")
			def headers = buildHeaders(null, username, password)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
			def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/vms/' + vmId + '/disks/' + diskAddress, null, null, requestOpts, 'PUT')
			log.info("ejectDisk results: ${results}")
			if (results.success == true && results.data) {
				def taskId = results.data.taskUuid
				def taskResults = checkTaskReady(client, opts, taskId)
				if (taskResults.success == true && taskResults.error != true) {
					rtn.taskUuid = taskId
					rtn.success = true
				} else {
					rtn.msg = 'eject disk failed'
				}
			}
		}
		return rtn
	}

	static addDisk(HttpApiClient client, opts, vmId, sizeGB, type) {
		log.debug("addDisk ${opts}, vm:${vmId}")
		def rtn = [success: false]
		if (!vmId) {
			rtn.error = 'Please specify a VM ID'
		} else if (!sizeGB) {
			rtn.error = 'Please specify a disk size'
		} else {
			def apiUrl = getNutanixApiUrl(opts.zone)
			def username = getNutanixUsername(opts.zone)
			def password = getNutanixPassword(opts.zone)
			def containerId = opts.containerId
			def diskSize = (int) sizeGB * 1024
			def vmDisks = []
			vmDisks << [vmDiskCreate: [sizeMb: diskSize, containerUuid: containerId], diskAddress: [deviceBus: type]]
			def body = [disks: vmDisks]
			log.info("add disk body: ${body}")
			def headers = buildHeaders(null, username, password)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
			def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/vms/' + vmId + '/disks', null, null, requestOpts, 'POST')

			log.info("addDisk results: ${results}")

			if (results.success == true && results.data) {
				def taskId = results.data.taskUuid
				def taskResults = checkTaskReady(client, opts, taskId)
				if (taskResults.success == true && taskResults.error != true) {
					rtn.taskUuid = taskId
					rtn.success = true
				} else {
					rtn.msg = 'add disk failed'
				}
			}
		}
		return rtn
	}

	static addNic(HttpApiClient client, opts, vmId) {
		log.debug("addNic ${opts}, vm:${vmId}")
		def rtn = [success: false]
		if (!vmId) {
			rtn.error = 'Please specify a VM ID'
		} else {
			def apiUrl = getNutanixApiUrl(opts.zone)
			def username = getNutanixUsername(opts.zone)
			def password = getNutanixPassword(opts.zone)
			def vmNics = []
			def vmNic = [networkUuid: opts.networkUuid]
			if (opts.ipAddress) {
				vmNic.requestIp = true
				vmNic.requestedIpAddress = opts.ipAddress
			}
			vmNics << vmNic
			def body = [specList: vmNics]
			log.info("add nic body: ${body}")
			def headers = buildHeaders(null, username, password)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
			def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/vms/' + vmId + '/nics', null, null, requestOpts, 'POST')

			log.info("addNic results: ${results}")
			if (results.success == true && results.data) {
				def taskId = results.data.taskUuid
				def taskResults = checkTaskReady(client, opts, taskId)
				if (taskResults.success == true && taskResults.error != true) {
					rtn.taskUuid = taskId
					rtn.success = true
				} else {
					rtn.msg = 'add nic failed'
				}
			}
		}
		return rtn
	}

	static addCdrom(HttpApiClient client, opts, vmId, cloudFileId, addr = null) {
		log.debug("addDisk ${opts}, vm:${vmId}")
		def rtn = [success: false]
		if (!vmId) {
			rtn.error = 'Please specify a VM ID'
		} else {
			def apiUrl = getNutanixApiUrl(opts.zone)
			def username = getNutanixUsername(opts.zone)
			def password = getNutanixPassword(opts.zone)
			def vmDisks = []
			vmDisks << [isCdrom: true, addr: addr, vmDiskClone: [vmDiskUuid: cloudFileId, minimumSize: (ComputeUtility.ONE_MEGABYTE)]]
			def body = [disks: vmDisks]
			log.info("add disk body: ${body}")
			def headers = buildHeaders(null, username, password)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
			def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/vms/' + vmId + '/disks', null, null, requestOpts, 'POST')

			log.info("addDisk results: ${results}")
			if (results.success == true && results.data) {
				def taskId = results.data.taskUuid
				def taskResults = checkTaskReady(client, opts, taskId)
				if (taskResults.success == true && taskResults.error != true) {
					rtn.taskUuid = taskId
					rtn.success = true
				} else {
					rtn.msg = 'add disk failed'
				}
			}
		}
		return rtn
	}

	static deleteDisk(HttpApiClient client, opts, vmId, diskAddress) {
		log.debug("deleteServerDisk ${opts}")
		def rtn = [success: false]
		if (!vmId) {
			rtn.error = 'Please specify a VM ID'
		} else if (!diskAddress) {
			rtn.error = 'Please specify a disk address'
		} else {
			def apiUrl = getNutanixApiUrl(opts.zone)
			def username = getNutanixUsername(opts.zone)
			def password = getNutanixPassword(opts.zone)
			def headers = buildHeaders(null, username, password)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
			def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/vms/' + vmId + '/disks/' + diskAddress, null, null, requestOpts, 'DELETE')

			log.info("deleteDisk: ${results}")
			if (results.success == true && results.data) {
				def taskId = results.data.taskUuid
				def taskResults = checkTaskReady(client, opts, taskId)
				if (taskResults.success == true && taskResults.error != true) {
					rtn.taskUuid = taskId
					rtn.success = true
				} else {
					rtn.msg = 'delete disk failed'
				}
			}
		}
		return rtn
	}

	static deleteNic(HttpApiClient client, opts, vmId, nicAddress) {
		log.debug("deleteNic ${opts}")
		def rtn = [success: false]
		if (!vmId) {
			rtn.error = 'Please specify a VM ID'
		} else if (!nicAddress) {
			rtn.error = 'Please specify a nic address'
		} else {
			def apiUrl = getNutanixApiUrl(opts.zone)
			def apiNumber = getNutanixApiNumber(opts.zone)
			def username = getNutanixUsername(opts.zone)
			def password = getNutanixPassword(opts.zone)
			def headers = buildHeaders(null, username, password)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
			def results = [success: false]
			if (apiNumber >= 2) {
				//requires MAC Address
				if (opts.macAddress) {
					nicAddress = opts.macAddress
				}
				results = client.callJsonApi(apiUrl, v2Api + 'vms/' + vmId + '/nics/' + nicAddress, null, null, requestOpts, 'DELETE')
			} else {
				results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/vms/' + vmId + '/nics/' + nicAddress, null, null, requestOpts, 'DELETE')
			}
			log.info("deleteNic: ${results}")
			if (results.success == true && results.data) {
				def taskId = results.data.taskUuid ?: results.data.task_uuid
				def taskResults = checkTaskReady(client, opts, taskId)
				if (taskResults.success == true && taskResults.error != true) {
					rtn.taskUuid = taskId
					rtn.success = true
				} else {
					rtn.msg = 'delete nic failed'
				}
			}
		}
		return rtn
	}

	static getConsoleUrl(HttpApiClient client, opts, vmId) {
		try {
			def apiUrl = getNutanixApiUrl(opts.zone)
			def username = getNutanixUsername(opts.zone)
			def password = getNutanixPassword(opts.zone)

			HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions()
			requestOpts.ignoreSSL = true
			requestOpts.headers = [
				'Accept': 'text/html',
			]
			requestOpts.body = [
				'j_username': username,
				'j_password': password,
			]
			def resp = client.callApi(apiUrl, "/PrismGateway/j_spring_security_check", null, null, requestOpts)
			if (resp.success) {
				def sessionCookie = resp.getCookie('JSESSIONID')
				if (sessionCookie != null) {

					def apiURL = new URI(apiUrl)
					return [success: true, url: "wss://${apiURL.host}:${apiURL.port}/vnc/vm/${vmId}/proxy", sessionCookie: sessionCookie]
				}
			}
		} catch (ex) {
			log.error("nutanix exception: ${ex.message}", ex)
		}
	}

	static cloneServer(HttpApiClient client, opts) {
		log.debug("cloneServer ${opts}")
		def rtn = [success: false]
		if (!opts.serverId && !opts.snapshotId) {
			rtn.error = 'Please specify a VM or Snapshot to clone'
		} else if (!opts.name) {
			rtn.error = 'Please specify a name for new VM'
		} else {
			def apiUrl = getNutanixApiUrl(opts.zone)
			def username = getNutanixUsername(opts.zone)
			def password = getNutanixPassword(opts.zone)
			def specs = [name: opts.name]
			if (opts.uuid)
				specs.uuid = opts.uuid
			def specList = [specs]
			def body = [specList: specList]
			if (opts.cloudConfig) {
				body.vmCustomizationConfig = [
					userdata: opts.cloudConfig
				]
				//if sysprep - its not an iso install
				if (opts.platform == 'platform')
					body.vmCustomizationConfig.fresh_install = false
			}
			log.info("clone server body: ${body}")
			def results
			def headers = buildHeaders(null, username, password)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)

			if (opts.snapshotId) {
				log.debug("cloning from snapshot ${opts.snapshotId}")
				results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/snapshots/' + opts.snapshotId + '/clone', null, null, requestOpts, 'POST')
			} else if (opts.serverId) {
				log.debug("cloning from server ${opts.serverId}")
				results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/vms/' + opts.serverId + '/clone', null, null, requestOpts, 'POST')
			}
			log.info("cloneServer: ${results}")
			if (results.success == true) {
				def taskId = results.data.taskUuid
				def taskResults = checkTaskReady(client, opts, taskId)
				if (taskResults.success != true && taskResults.errorCode == 500 && opts.uuid) {
					def vmCheckResults = checkVmReady(client, opts, opts.uuid)
					if (vmCheckResults.success == true)
						taskResults = [success: true, error: false, results: [entityList: [[uuid: opts.uuid]], entity_lst: [[entity_id: opts.uuid]]]]
				}
				if (taskResults.success == true && taskResults.error != true) {
					def serverId = taskResults.results.entity_list[0].entity_id
					def serverResults = findVirtualMachine(client, opts, opts.name)
					if (serverResults.success == true) {
						def vm = serverResults?.virtualMachine
						if (vm) {
							if (opts.cloudFileId) {
								log.debug("CDROM Detected on Nutanix Clone, Swapping out cloud init file!")
								def cdromDisk = getVirtualMachineDisks(client, opts, vm.uuid)?.disks?.find { it.isCdrom }
								if (cdromDisk) {
									deleteDisk(client, opts, vm.uuid, cdromDisk.id)
								}
								addCdrom(client, opts, vm.uuid, opts.cloudFileId, cdromDisk?.addr ?: ['deviceBus': 'ide', 'deviceIndex': 0])
							}
							rtn.taskUuid = taskId
							rtn.results = vm
							rtn.serverId = vm.uuid
							rtn.completeTime = taskResults.completeTime
							rtn.success = true
						}
					} else {
						rtn.msg = 'could not find template'
					}
				} else {
					rtn.msg = 'task failed'
				}
			}
		}
		return rtn
	}

	static startVm(HttpApiClient client, opts, serverId) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def body = [logicalTimestamp: (opts.timestamp ?: 1)]
		//cache
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
		def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/vms/' + serverId + '/power_op/on', null, null, requestOpts, 'POST')
		log.debug("startVm: ${results}")
		if (results.success == true && results.data) {
			def taskId = results.data.taskUuid
			def taskResults = checkTaskReady(client, opts, taskId)
			def taskSuccess = taskResults.success == true && (taskResults.error != true || taskResults.results?.metaResponse?.error == 'kInvalidState')
			if (taskSuccess == true) {
				rtn.taskUuid = taskId
				rtn.success = true
			} else {
				rtn.msg = 'power on failed'
			}
		}
		return rtn
	}

	static stopVm(HttpApiClient client, opts, serverId) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		//loadVirtualMachine and check power status. Nutanix fails task if VM already powered off.
		def vmResult = loadVirtualMachine(client, opts, serverId)
		if (vmResult?.success) {
			if (vmResult.vmDetails?.powerState && vmResult.vmDetails?.powerState?.toLowerCase() != "off") {
				def body = [logicalTimestamp: (opts.timestamp ?: 1)]
				//cache
				def headers = buildHeaders(null, username, password)
				def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
				def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/vms/' + serverId + '/power_op/off', null, null, requestOpts, 'POST')
				log.debug("stopVm: ${results}")
				if (results.success == true && results.data) {
					def taskId = results.data.taskUuid
					def taskResults = checkTaskReady(client, opts, taskId)
					def taskSuccess = taskResults.success == true && (taskResults.error != true || taskResults.results?.metaResponse?.error == 'kInvalidState')
					if (taskSuccess == true) {
						rtn.taskUuid = taskId
						rtn.success = true
					} else {
						rtn.success = false
						rtn.msg = 'power off failed'
					}
				}
			} else {
				//powered off so do nothing
				rtn.success = true
			}
		} else {
			rtn.msg = "VM not found: ${serverId}"
		}
		return rtn
	}

	static deleteServer(HttpApiClient client, opts, serverId) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		//cache
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/vms/' + serverId + '/', null, null, requestOpts, 'DELETE')
		log.debug("deleteVm: ${results}")
		if (results.success == true && results.data) {
			def taskId = results.data.taskUuid
			def taskResults = checkTaskReady(client, opts, taskId)
			if (taskResults.success == true && taskResults.error != true) {
				rtn.taskUuid = taskId
				rtn.success = true
			} else {
				rtn.msg = 'delete failed'
			}
		}
		return rtn
	}

	static deleteImage(HttpApiClient client, opts, imageId) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		//cache
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/images/' + imageId + '/', null, null, requestOpts, 'DELETE')
		log.debug("deleteImage: ${results}")
		if (results.success == true && results.data) {
			def taskId = results.data.taskUuid
			def taskResults = checkTaskReady(client, opts, taskId)
			if (taskResults.success == true && taskResults.error != true) {
				rtn.taskUuid = taskId
				rtn.success = true
			} else {
				rtn.msg = 'delete failed'
			}
		}
		return rtn
	}

	static listSnapshots(HttpApiClient client, Map authConfig) {
		def rtn = [success: false]

		def headers = buildHeaders(null, authConfig.username, authConfig.password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(authConfig.apiUrl, '/api/nutanix/' + authConfig.apiVersion + '/snapshots/', null, null, requestOpts, 'GET')
		rtn.success = results?.success && results?.error != true
		if (rtn.success == true) {
			rtn.results = results.data
			log.trace("listSnapshots: ${rtn.results}")
		}
		return rtn
	}

	static getSnapshot(HttpApiClient client, opts, snapshotId) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)

		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/snapshots/' + snapshotId, null, null, requestOpts, 'GET')

		rtn.success = results?.success && results?.error != true
		if (rtn.success == true) {
			rtn.results = results.data //new groovy.json.JsonSlurper().parseText(results.content)
			log.debug("task results: ${rtn.results}")
		} else if (results?.error == true) {
			rtn.errorCode = results.errorCode
			rtn.error == true
		}
		return rtn
	}

	static createSnapshot(HttpApiClient client, opts) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def vmUuid
		def vmResult = loadVirtualMachine(client, opts, opts.vmId)
		if (vmResult?.success) {
			vmUuid = vmResult.vmDetails.vmId
			//def snapshotUuid = java.util.UUID.randomUUID().toString()
			//def body = [snapshotSpecs:[[vmUuid:vmUuid, uuid:snapshotUuid, snapshotName:opts.snapshotName]]]
			def body = [snapshotSpecs: [[vmUuid: vmUuid, snapshotName: opts.snapshotName]]]
			log.info("Create snapshot body: ${body}")
			def headers = buildHeaders(null, username, password)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
			def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/snapshots/', null, null, requestOpts, 'POST')

			rtn.success = results?.success && results?.error != true
			if (rtn.success == true) {
				//rtn.snapshotUuid = snapshotUuid
				rtn.results = results.data
				log.trace("createSnapshot: ${rtn.results}")
			}
		} else {
			rtn.msg = "VM not found: ${opts.vmId}"
		}
		return rtn
	}

	static restoreSnapshot(HttpApiClient client, opts) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def vmUuid
		def vmResult = loadVirtualMachine(client, opts, opts.vmId)
		if (vmResult?.success) {
			vmUuid = vmResult.vmDetails.vmId

			def body = [restore_network_configuration: true, snapshot_uuid: opts.snapshotId, uuid: vmUuid]
			log.info("Create snapshot body: ${body}")
			def headers = buildHeaders(null, username, password)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
			def results = client.callJsonApi(apiUrl, v2Api + 'vms/' + vmUuid + '/restore', null, null, requestOpts, 'POST')
			rtn.success = results?.success && results?.error != true
			if (rtn.success == true) {
				rtn.results = results.data
				log.info("restoreSnapshot: ${rtn.results}")
			}
		} else {
			rtn.msg = "VM not found: ${opts.vmId}"
		}
		return rtn
	}

	static deleteSnapshot(HttpApiClient client, opts, snapshotId) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(apiUrl, '/api/nutanix/v0.8/snapshots/' + snapshotId + '/', null, null, requestOpts, 'DELETE')
		log.debug("deleteSnapshot: ${results}")
		if (results.success == true && results.data) {
			def taskId = results.data.taskUuid
			def taskResults = checkTaskReady(client, opts, taskId)
			if (taskResults.success == true && taskResults.error != true) {
				rtn.taskUuid = taskId
				rtn.success = true
			} else {
				rtn.msg = 'delete failed'
			}
		}
		return rtn
	}

	static checkIpv4Ip(ipAddress) {
		def rtn = false
		if (ipAddress) {
			if (ipAddress.indexOf('.') > 0 && !ipAddress.startsWith('169'))
				rtn = true
		}
		return rtn
	}

	static checkServerReady(HttpApiClient client, opts, vmId) {
		def rtn = [success: false]
		try {
			def pending = true
			def attempts = 0
			while (pending) {
				sleep(1000l * 20l)
				def serverDetail = loadVirtualMachine(client, opts, vmId)
				log.debug("serverDetail: ${serverDetail}")
				if (serverDetail.success == true && serverDetail.virtualMachine.state == 'on' && serverDetail.vmDetails.ipAddresses?.size() > 0 && serverDetail.vmDetails.ipAddresses.find { checkIpv4Ip(it) }) {
					if (serverDetail.virtualMachine.state == 'on') {
						rtn.success = true
						rtn.virtualMachine = serverDetail.virtualMachine
						rtn.vmDetails = serverDetail.vmDetails
						serverDetail.vmDetails.ipAddresses = serverDetail.vmDetails.ipAddresses.findAll { checkIpv4Ip(it) }
						pending = false
					} else if (serverDetail.virtualMachine.state == 'off') {
						rtn.error = true
						rtn.results = serverDetail.results
						rtn.success = true
						pending = false
					}
				}
				attempts++
				if (attempts > 60)
					pending = false
			}
		} catch (e) {
			log.error("An Exception Has Occurred", e)
		}
		return rtn
	}

	static insertContainerImage(HttpApiClient client, opts) {
		def rtn = [success: false]
		log.info("insertContainerImage: ${opts}")
		def image = opts.image
		def matchResults = findImage(client, opts, image.name)
		def match = matchResults.image
		if (match) {
			log.debug("using found image")
			rtn.imageId = match.uuid
			rtn.imageDiskId = match.vmDiskId
			rtn.success = true
		} else {
			log.debug("inserting image")
			def createResults = uploadImage(client, opts)
			if (createResults.success == true) {
				//wait here?
				def taskId = createResults.taskUuid
				def taskResults = checkTaskReady(client, opts, taskId)
				if (taskResults.success == true && taskResults.error != true) {
					def imageId = taskResults.results.entity_list[0].entity_id
					def imageResults = findImage(client, opts, image.name)
					if (imageResults.success == true) {
						def vmImage = imageResults?.image
						if (vmImage) {
							rtn.imageId = vmImage.uuid
							rtn.imageDiskId = vmImage.vmDiskId
							rtn.success = true
						}
					} else {
						rtn.msg = 'could not find template'
					}
				} else {
					//if https lets try at http - nutanix validates certs
					def imageUrl = image.imageUrl ?: image.imageFile.getURL(new Date(new Date().time + 20l * 60000l)).toString()
					if (imageUrl.startsWith('https')) {
						imageUrl = imageUrl.replaceAll('https', 'http')
						opts.image.imageUrl = imageUrl
						return insertContainerImage(client, opts)
					} else {
						rtn.msg = 'task failed'
					}
				}
			}
		}
		return rtn
	}

	static checkTaskReady(HttpApiClient client, opts, taskId) {
		def rtn = [success: false]
		try {
			if (taskId == null)
				return rtn
			def pending = true
			def attempts = 0
			while (pending) {
				sleep(1000l * 10l)
				def taskDetail = getTask(client, opts, taskId)
				log.info("taskDetail - ${taskId}: ${taskDetail}")
				if (taskDetail.success == true && taskDetail.results.progress_status) {
					if (taskDetail.results.progress_status == 'Succeeded') {
						rtn.success = true
						rtn.results = taskDetail.results
						pending = false
					} else if (taskDetail.results.progress_status == 'Failure' || taskDetail.results.progress_status == 'Failed') {
						rtn.error = true
						rtn.results = taskDetail.results
						rtn.success = true
						pending = false
					}
				} else if (taskDetail.errorCode == 500) {
					log.warn("task api call - 500 error (bug in nutanix)")
					rtn.errorCode = taskDetail.errorCode
					pending = false
				}
				attempts++
				if (attempts > 350)
					pending = false
			}
		} catch (e) {
			log.error("An Exception Has Occurred", e)
		}
		return rtn
	}

	static checkVmReady(HttpApiClient client, opts, vmId) {
		def rtn = [success: false]
		try {
			if (vmId == null)
				return rtn
			def pending = true
			def attempts = 0
			while (pending) {
				sleep(1000l * 10l)
				def vmDetails = loadVirtualMachine(client, opts, vmId)
				log.debug("vmDetails - ${vmId}: ${vmDetails}")
				if (vmDetails.success == true && vmDetails.vmDetails?.powerState) {
					rtn.success = true
					rtn.results = vmDetails
					pending = false
				}
				attempts++
				if (attempts > 150)
					pending = false
			}
		} catch (e) {
			log.error("An Exception Has Occurred", e)
		}
		return rtn
	}

	//v3
	static buildHeaders(Map headers, String username, String password) {
		headers = (headers ?: [:]) + ['Content-Type': 'application/json;', 'Accept': 'application/json']
		if (username && password) {
			def creds = "${username}:${password}"
			headers['Authorization'] = "Basic ${creds.getBytes().encodeBase64().toString()}".toString()
		}
		return headers
	}

	//utils
	static getNutanixApiUrl(zone) {
		def apiUrl = zone.serviceUrl ?: zone.getConfigMap()?.apiUrl
		if (apiUrl) {
			if (apiUrl.startsWith('http')) {
				URIBuilder uriBuilder = new URIBuilder("${apiUrl}")
				uriBuilder.setPath('')
				return uriBuilder.build().toString()
			}
			return 'https://' + apiUrl + ':9440'
		}
		throw new Exception('no nutanix api url specified')
	}

	static getNutanixUsername(zone) {
		def rtn = zone.serviceUsername ?: zone.credentialData?.username ?: zone.getConfigProperty('username')
		if (!rtn) {
			throw new Exception('no nutanix username specified')
		}
		return rtn
	}

	static getNutanixPassword(zone) {
		def rtn = zone.servicePassword ?: zone.credentialData?.password ?: zone.getConfigProperty('password')
		if (!rtn) {
			throw new Exception('no nutanix password specified')
		}
		return rtn
	}

	static getNutanixApiVersion(Cloud cloud) {
		return cloud.serviceVersion ?: 'v1'
	}

	static getNutanixApiNumber(Cloud cloud) {
		return getNutanixApiVersion(cloud)?.replace('v', '')?.toDouble() ?: 1.0
	}
}
